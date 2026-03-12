"""Gemini decomposer — large context breakdown into thinking map vertices.

When the total context exceeds GPU/OpenRouter limits (>100k tokens), uses Gemini's
1M context window to decompose it into smaller self-contained sub-tasks.
Each sub-task becomes a vertex in the thinking map, processed independently
within normal context limits. A SYNTHESIS vertex combines results.

Flow:
1. Check if content exceeds threshold AND Gemini is available
2. Send entire context to Gemini with decomposition instructions
3. Parse response into vertices + edges
4. Create SYNTHESIS vertex that depends on all sub-vertices
5. Each vertex executed independently by vertex_executor
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from app.config import settings
from app.llm.provider import (
    check_gemini_available,
    increment_gemini_counter,
    TIER_CONFIG,
    TIER_TIMEOUT_SECONDS,
)
from app.config import estimate_tokens
from app.agent.graph import add_vertex, add_edge, complete_vertex
from app.agent.models import (
    AgentGraph,
    EdgeType,
    GraphVertex,
    VertexStatus,
    VertexType,
)
from app.models import ModelTier

if TYPE_CHECKING:
    pass

logger = logging.getLogger(__name__)

# Threshold: context above this triggers Gemini decomposition
GEMINI_DECOMPOSE_THRESHOLD_TOKENS = 100_000


def should_use_gemini_decomposition(content: str, state: dict) -> bool:
    """Check if context is large enough and Gemini is available for decomposition."""
    token_count = estimate_tokens(content)
    if token_count < GEMINI_DECOMPOSE_THRESHOLD_TOKENS:
        return False
    if not check_gemini_available():
        logger.info("Gemini decomposition skipped: daily limit reached")
        return False
    logger.info(
        "Large context detected (%dk tokens > %dk threshold), Gemini decomposition eligible",
        token_count // 1000,
        GEMINI_DECOMPOSE_THRESHOLD_TOKENS // 1000,
    )
    return True


_GEMINI_DECOMPOSE_PROMPT = """You are a Large Context Decomposition Engine.

You received an extremely large context (document, codebase, book chapter, etc.).
Your job is to break it into self-contained sub-tasks that can each be processed
independently within a smaller context window (~48k tokens).

## Rules

- Each sub-task MUST include the relevant excerpt from the original context
- Excerpts should be self-contained — a reader should understand the sub-task
  without needing the full document
- Maximum 10 sub-tasks
- Each sub-task description should specify WHAT to analyze/process and include
  the relevant context excerpt inline
- Order sub-tasks logically (e.g., foundations before details)
- The synthesis task combines ALL results into a coherent final output

## Response format (JSON)

```json
{
  "sub_tasks": [
    {
      "title": "<short descriptive title>",
      "description": "<what to analyze/process>",
      "excerpt": "<relevant excerpt from the context, up to 40k chars>"
    }
  ],
  "synthesis_instruction": "<how to combine all sub-task results into the final answer>"
}
```
"""


async def decompose_large_context(
    graph: AgentGraph,
    parent_id: str,
    content: str,
    objective: str,
    state: dict,
) -> AgentGraph:
    """Use Gemini to decompose large context into sub-vertices in the thinking map.

    Args:
        graph: The thinking map graph
        parent_id: Parent vertex ID (root or decompose vertex)
        content: The large context to decompose
        objective: What the user wants to achieve with this context
        state: Pipeline state dict (for rules, task info)

    Returns:
        Modified graph with new sub-vertices and synthesis vertex.
    """
    import litellm

    parent = graph.vertices.get(parent_id)
    if not parent:
        raise ValueError(f"Parent vertex {parent_id} not found")

    logger.info("Gemini decomposition: content=%dk tokens, objective=%s",
                estimate_tokens(content) // 1000, objective[:100])

    tier = ModelTier.CLOUD_LARGE_CONTEXT
    tier_config = TIER_CONFIG.get(tier, {})
    model = tier_config.get("model", f"google/{settings.default_large_context_model}")
    timeout = TIER_TIMEOUT_SECONDS.get(tier, 300)

    messages = [
        {"role": "system", "content": _GEMINI_DECOMPOSE_PROMPT},
        {"role": "user", "content": (
            f"## Objective\n{objective}\n\n"
            f"## Full Context\n{content}"
        )},
    ]

    try:
        response = await litellm.acompletion(
            model=model,
            messages=messages,
            max_tokens=8192,
            temperature=0.1,
            timeout=timeout,
        )
        increment_gemini_counter()

        resp_content = response.choices[0].message.content or ""
        data = _parse_json(resp_content)

        if not data or "sub_tasks" not in data:
            logger.warning("Gemini decomposition returned no valid sub_tasks")
            return graph

        sub_tasks = data["sub_tasks"]
        if not isinstance(sub_tasks, list) or not sub_tasks:
            return graph

        # Limit to 10
        sub_tasks = sub_tasks[:10]

        # Create sub-vertices
        created_vertices: list[GraphVertex] = []
        for st in sub_tasks:
            title = st.get("title", "Sub-task")
            description = st.get("description", "")
            excerpt = st.get("excerpt", "")

            # Combine description + excerpt as the vertex input
            full_description = f"{description}\n\n## Context Excerpt\n{excerpt}" if excerpt else description

            v = add_vertex(
                graph=graph,
                title=title,
                description=full_description,
                vertex_type=VertexType.TASK,
                parent_id=parent_id,
                input_request=full_description,
                client_id=parent.client_id if parent else "",
                project_id=parent.project_id if parent else "",
            )
            add_edge(graph, parent_id, v.id, EdgeType.DECOMPOSITION)
            v.status = VertexStatus.READY
            created_vertices.append(v)

        # Create SYNTHESIS vertex
        synthesis_instruction = data.get("synthesis_instruction", "Combine all sub-task results into a coherent answer.")
        synthesis_v = add_vertex(
            graph=graph,
            title="Syntéza výsledků",
            description=synthesis_instruction,
            vertex_type=VertexType.SYNTHESIS,
            parent_id=parent_id,
            input_request=synthesis_instruction,
            client_id=parent.client_id if parent else "",
            project_id=parent.project_id if parent else "",
        )

        # Synthesis depends on all sub-vertices
        for cv in created_vertices:
            add_edge(graph, cv.id, synthesis_v.id, EdgeType.DEPENDENCY)

        # Mark parent as completed via complete_vertex() — fills outgoing edge payloads
        complete_vertex(
            graph, parent_id,
            result=f"Decomposed via Gemini into {len(created_vertices)} sub-tasks + synthesis",
            result_summary=f"Decomposed via Gemini into {len(created_vertices)} sub-tasks + synthesis",
        )

        logger.info(
            "Gemini decomposition created %d sub-vertices + synthesis for parent %s",
            len(created_vertices), parent_id,
        )

        return graph

    except Exception as e:
        logger.error("Gemini decomposition failed: %s", e, exc_info=True)
        return graph


def _parse_json(text: str) -> dict | None:
    """Extract JSON from LLM response (may be wrapped in ```json blocks)."""
    import json
    import re

    # Try direct parse
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # Try extracting from ```json ... ```
    match = re.search(r"```json?\s*\n?(.*?)\n?\s*```", text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError:
            pass

    return None
