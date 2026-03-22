"""Foreground chat decomposition — detect multi-entity queries and split into graph vertices.

Instead of running one agentic loop with 54k context for a multi-entity query
(e.g. "find 10 restaurants with weekend menus"), this module:
1. Detects that the query requires parallel research on multiple entities
2. Creates an AgentGraph with INVESTIGATOR vertices (one per entity)
3. Each vertex runs with ~5k context instead of 54k
4. Results are synthesized into a single response

Detection is done via a quick LLM call (LOCAL_COMPACT, minimal context).
The graph execution reuses the existing LangGraph runner infrastructure.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass

from app.agent.graph import add_edge, add_vertex, create_task_graph
from app.agent.models import (
    AgentGraph,
    EdgeType,
    VertexStatus,
    VertexType,
)
from app.chat.handler_streaming import call_llm
from app.config import settings
from app.graph.nodes._helpers import parse_json_response
from app.models import ModelTier

logger = logging.getLogger(__name__)


@dataclass
class DecompositionResult:
    """Result of decomposition detection."""
    should_decompose: bool
    graph: AgentGraph | None = None
    subtasks: list[dict] | None = None  # [{title, description}]
    reason: str = ""


async def detect_and_decompose(
    user_message: str,
    conversation_summary: str = "",
    client_id: str = "",
    project_id: str = "",
    max_openrouter_tier: str = "NONE",
) -> DecompositionResult:
    """Detect if a chat message should be decomposed into parallel graph vertices.

    Uses a quick LLM call with minimal context to classify the message.
    Only decomposes when there are clearly independent sub-tasks that
    benefit from parallel execution with small context.

    Args:
        user_message: The user's chat message
        conversation_summary: Brief summary of recent conversation (optional)
        client_id: Active client ID
        project_id: Active project ID

    Returns:
        DecompositionResult with graph if decomposition is needed
    """
    # Quick heuristic pre-filter: skip very short messages or simple questions
    if len(user_message) < 30:
        return DecompositionResult(should_decompose=False, reason="too_short")

    try:
        # Route decomposition via OpenRouter when client has cloud tier
        route = None
        tier = ModelTier.LOCAL_COMPACT
        if max_openrouter_tier and max_openrouter_tier != "NONE":
            from app.llm.router_client import route_request
            route = await route_request(
                capability="chat",
                max_tier=max_openrouter_tier,
                estimated_tokens=2000,  # Small classification prompt
            )

        # Build messages with optional conversation context
        decompose_messages = [
            {
                "role": "system",
                "content": (
                    "You are a task decomposition classifier. Analyze the user message and decide "
                    "if it requires PARALLEL RESEARCH on multiple entities.\n\n"
                    "DECOMPOSE when ANY of these applies:\n"
                    "1. NAMED ENTITIES: User mentions multiple specific entities to research "
                    "(e.g. 'compare restaurants Miura, Zappas, Lorková Vila')\n"
                    "2. DISCOVERY + RESEARCH: User wants to find/compare TOP N items in a category "
                    "where each item needs independent detailed research "
                    "(e.g. 'top 10 restaurants near X with ratings and menus', "
                    "'best 5 hotels in Prague with reviews', "
                    "'find coworking spaces in Brno with pricing').\n"
                    "   For discovery: first create a DISCOVERY subtask that finds the list, "
                    "   then create one subtask per entity mentioned as example in the message.\n"
                    "   The discovery subtask MUST be named 'Discovery: <topic>'.\n"
                    "3. MULTI-ASPECT: User wants multiple independent aspects researched "
                    "(e.g. 'check weather, restaurants and hotels in X')\n\n"
                    "Each entity MUST be researchable INDEPENDENTLY and in PARALLEL.\n"
                    "The message must be a NEW REQUEST (not a follow-up).\n\n"
                    "DO NOT decompose:\n"
                    "- Follow-up messages ('seřaď to', 'výsledky jsou chaotické', 'jsou ověřené?')\n"
                    "- Simple single-entity questions\n"
                    "- Conversational messages (greeting, confirmation)\n"
                    "- References to 'výsledky', 'odpověď', 'data' from earlier conversation\n\n"
                    "Respond in JSON:\n"
                    '{"decompose": true/false, "subtasks": [{"title": "...", "description": "..."}], '
                    '"reason": "brief explanation"}\n\n'
                    "If decompose=false, subtasks should be empty [].\n"
                    "Each subtask title MUST be a specific entity or 'Discovery: <topic>'.\n"
                    "NEVER use placeholders like 'Restaurant A'.\n"
                    "Keep descriptions concise — each vertex will use web_search and web_fetch tools."
                ),
            },
        ]
        # Add conversation context so decomposer understands follow-ups
        if conversation_summary:
            decompose_messages.append({
                "role": "system",
                "content": f"Recent conversation context:\n{conversation_summary}",
            })
        decompose_messages.append({
            "role": "user",
            "content": user_message,
        })

        response = await call_llm(
            messages=decompose_messages,
            tier=tier,
            max_tokens=1024,
            route=route,
            max_tier=max_openrouter_tier,
        )

        text = response.choices[0].message.content or ""
        parsed = parse_json_response(text)

        if not parsed or not parsed.get("decompose"):
            _reason = parsed.get("reason", "llm_said_no") if parsed else "parse_failed"
            logger.info("CHAT_DECOMPOSE | NO | reason=%s | message='%s'", _reason, user_message[:100])
            return DecompositionResult(
                should_decompose=False,
                reason=_reason,
            )

        subtasks = parsed.get("subtasks", [])
        if len(subtasks) < 2:
            return DecompositionResult(
                should_decompose=False,
                reason="fewer_than_2_subtasks",
            )

        # Cap subtasks
        subtasks = subtasks[:15]

        # Create AgentGraph
        graph = _create_foreground_graph(
            user_message=user_message,
            subtasks=subtasks,
            client_id=client_id,
            project_id=project_id,
        )

        logger.info(
            "CHAT_DECOMPOSE | subtasks=%d | reason=%s | message='%s'",
            len(subtasks), parsed.get("reason", ""), user_message[:100],
        )

        return DecompositionResult(
            should_decompose=True,
            graph=graph,
            subtasks=subtasks,
            reason=parsed.get("reason", "multi_entity"),
        )

    except Exception as e:
        logger.warning("Chat decomposition detection failed: %s", e)
        return DecompositionResult(should_decompose=False, reason=f"error: {e}")


def _create_foreground_graph(
    user_message: str,
    subtasks: list[dict],
    client_id: str,
    project_id: str,
) -> AgentGraph:
    """Create an AgentGraph for foreground chat decomposition.

    Structure:
        ROOT (user's original question)
        ├── INVESTIGATOR 1 (entity 1)
        ├── INVESTIGATOR 2 (entity 2)
        └── ...
        SYNTHESIS vertex (depends on all investigators)

    Each investigator gets only its specific sub-task description as context.
    The synthesis vertex receives results from all investigators via edge payloads.
    """
    task_id = f"chat-fg-{int(time.time())}"

    graph = create_task_graph(
        task_id=task_id,
        client_id=client_id,
        project_id=project_id,
        root_title=user_message[:100],
        root_description=user_message,
    )

    # Root vertex is just a placeholder in foreground graphs —
    # mark it COMPLETED immediately so it never resumes.
    # The Synthesis vertex handles combining results.
    root = graph.vertices[graph.root_vertex_id]
    root.vertex_type = VertexType.TASK
    root.status = VertexStatus.COMPLETED
    root.result = "Decomposed into sub-tasks"
    root.result_summary = "Decomposed into sub-tasks"

    # Create synthesis vertex (will run after all investigators complete)
    synthesis = add_vertex(
        graph=graph,
        title="Synthesis",
        description=(
            f"Combine results from {len(subtasks)} sub-tasks into a coherent response.\n"
            f"Original question: {user_message}\n"
            "Respond in the same language as the original question."
        ),
        vertex_type=VertexType.SYNTHESIS,
        parent_id=root.id,
        input_request=user_message,
        client_id=client_id,
        project_id=project_id,
    )
    synthesis.status = VertexStatus.PENDING  # Waits for all investigators

    # Create investigator vertices
    for spec in subtasks:
        title = (spec.get("title") or "Untitled").strip()
        description = (spec.get("description") or title).strip()

        investigator = add_vertex(
            graph=graph,
            title=title,
            description=description,
            vertex_type=VertexType.INVESTIGATOR,
            parent_id=root.id,
            input_request=description,
            client_id=client_id,
            project_id=project_id,
        )
        investigator.status = VertexStatus.READY  # Can run immediately

        # DECOMPOSITION edge from root (traceability)
        add_edge(graph, root.id, investigator.id, EdgeType.DECOMPOSITION)

        # DEPENDENCY edge to synthesis (gates readiness)
        add_edge(graph, investigator.id, synthesis.id, EdgeType.DEPENDENCY)

    return graph
