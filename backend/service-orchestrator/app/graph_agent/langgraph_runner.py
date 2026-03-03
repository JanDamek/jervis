"""LangGraph-based runner for Graph Agent.

Uses LangGraph (proven framework) for execution, with TaskGraph as the
planning structure. Each vertex type maps to a LangGraph node handler
that performs a specific responsibility:

  PLANNER      → decomposes / plans approach
  INVESTIGATOR → researches context (KB, web, code)
  EXECUTOR     → performs concrete work (coding, tracker)
  VALIDATOR    → verifies results (tests, lint, checks)
  REVIEWER     → reviews quality (code review, output review)
  SYNTHESIS    → combines results from upstream vertices
  GATE         → decision / approval point
  SETUP        → project scaffolding + environment provisioning

The graph is a loop:
  decompose → select_next → dispatch_vertex → complete_vertex → [loop back to select_next]
                                                              → synthesize (when all done)

TaskGraph is carried in LangGraph state — LangGraph handles checkpointing,
interrupt/resume, and execution flow. We just teach it HOW to think.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import TypedDict

from pymongo import MongoClient
from langgraph.checkpoint.mongodb import MongoDBSaver
from langgraph.graph import END, StateGraph
from langgraph.types import interrupt

from app.config import settings
from app.graph.nodes._helpers import (
    detect_tool_loop,
    llm_with_cloud_fallback,
    parse_json_response,
)
from app.graph_agent.decomposer import decompose_root, decompose_vertex
from app.graph_agent.graph import (
    complete_vertex,
    fail_vertex,
    get_ready_vertices,
    start_vertex,
    get_final_result,
    get_stats,
)
from app.graph_agent.models import (
    EdgeType,
    GraphStatus,
    GraphVertex,
    TaskGraph,
    VertexStatus,
    VertexType,
)
from app.graph_agent.graph import create_task_graph
from app.graph_agent.persistence import task_graph_store
from app.graph_agent.progress import (
    report_decomposition_progress,
    report_graph_status,
    report_vertex_completed,
    report_vertex_started,
)
from app.graph_agent.impact import analyze_impact
from app.graph_agent.tool_sets import get_default_tools, get_tools_by_category
from app.graph_agent.validation import validate_graph
from app.models import ChatHistoryPayload, CodingTask, DelegationMessage, OrchestrateRequest
from app.tools.executor import AskUserInterrupt, execute_tool
from app.tools.ollama_parsing import extract_tool_calls

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# LangGraph State
# ---------------------------------------------------------------------------


class GraphAgentState(TypedDict, total=False):
    """LangGraph state for the Graph Agent."""

    # Core task data (same as OrchestratorState)
    task: dict
    rules: dict
    environment: dict | None
    chat_history: dict | None
    processing_mode: str | None
    response_language: str
    allow_cloud_prompt: bool

    # Graph Agent specific
    task_graph: dict | None             # TaskGraph serialized
    current_vertex_id: str | None       # Vertex being processed
    ready_vertex_ids: list[str]         # All READY vertices for parallel execution
    graph_error: str | None             # Error if graph-level failure
    final_result: str | None            # Composed final result


# ---------------------------------------------------------------------------
# Node: decompose
# ---------------------------------------------------------------------------


async def node_decompose(state: GraphAgentState) -> dict:
    """Decompose the user request into a TaskGraph (vertices + edges).

    ALWAYS goes through LLM decomposition — even simple questions like
    "kolik je hodin?" because complexity can't be judged from text length.
    ("jaký je stav projektu?" is short but needs deep analysis.)

    The decomposer LLM decides: simple → 1 vertex, complex → multiple vertices.
    """
    task = CodingTask(**state["task"])
    evidence = state.get("evidence_pack")

    # Create graph with root vertex
    graph = create_task_graph(
        task_id=task.id,
        client_id=task.client_id,
        project_id=task.project_id,
        root_title=task.query[:100],
        root_description=task.query,
    )

    # Fetch existing resources context for the decomposer
    resource_context = await _fetch_resource_context(task.client_id, task.project_id)
    if evidence is None:
        evidence = {}
    if resource_context:
        evidence["existing_resources"] = resource_context

    # Include chat history for iterative requirement building
    # (user may have described requirements across multiple messages)
    chat_history_data = state.get("chat_history")
    if chat_history_data:
        chat_payload = ChatHistoryPayload(**chat_history_data) if isinstance(chat_history_data, dict) else chat_history_data
        summary_parts = []
        for block in chat_payload.summary_blocks:
            summary_parts.append(block.summary)
        for msg in chat_payload.recent_messages:
            summary_parts.append(f"[{msg.role}] {msg.content}")
        if summary_parts:
            evidence["chat_history_summary"] = "\n".join(summary_parts)

    # LLM-driven decomposition — LLM decides complexity, not heuristics
    try:
        graph = await decompose_root(
            graph=graph,
            state=state,
            evidence=evidence,
            guidelines="",
        )
    except Exception as e:
        logger.error("Decomposition failed: %s", e, exc_info=True)
        return {
            "task_graph": None,
            "graph_error": f"Decomposition failed: {e}",
            "final_result": f"Error: decomposition failed — {e}",
        }

    # Validate
    validation = validate_graph(graph)
    if not validation.valid:
        error_msg = "; ".join(validation.errors)
        logger.error("Graph validation failed: %s", error_msg)
        return {
            "task_graph": None,
            "graph_error": f"Validation failed: {error_msg}",
            "final_result": f"Error: graph validation failed — {error_msg}",
        }

    # Persist and store in state
    await task_graph_store.save(graph)
    await report_graph_status(graph, f"Decomposed into {len(graph.vertices) - 1} vertices")

    return {
        "task_graph": graph.model_dump(),
        "current_vertex_id": None,
        "graph_error": None,
    }


# ---------------------------------------------------------------------------
# Node: select_next
# ---------------------------------------------------------------------------


async def node_select_next(state: GraphAgentState) -> dict:
    """Find the next READY vertex to execute.

    A vertex is READY when all its incoming edges have payloads.
    Returns the first ready vertex ID, or None if all done.
    """
    graph_data = state.get("task_graph")
    if not graph_data:
        return {"current_vertex_id": None}

    graph = TaskGraph(**graph_data)

    # Cancellation check — stop scheduling new vertices if cancelled
    if graph.status == GraphStatus.CANCELLED:
        logger.info("Graph %s cancelled — stopping vertex scheduling", graph.id)
        return {"task_graph": graph.model_dump(), "current_vertex_id": None}

    ready = get_ready_vertices(graph)

    if not ready:
        return {
            "task_graph": graph.model_dump(),
            "current_vertex_id": None,
            "ready_vertex_ids": [],
        }

    # Return all ready vertices for parallel execution
    ready_ids = [v.id for v in ready]
    return {
        "task_graph": graph.model_dump(),
        "current_vertex_id": ready_ids[0],
        "ready_vertex_ids": ready_ids,
    }


# ---------------------------------------------------------------------------
# Node: dispatch_vertex — routes to type-specific handler
# ---------------------------------------------------------------------------


async def node_dispatch_vertex(state: GraphAgentState) -> dict:
    """Execute ready vertices — parallel when multiple are ready.

    Each VertexType maps to a specific handler:
    - PLANNER/DECOMPOSE → recursive decomposition
    - All others        → agentic tool loop
    """
    graph = TaskGraph(**state["task_graph"])
    ready_ids = state.get("ready_vertex_ids", [])

    # Fallback to single vertex for backward compat
    if not ready_ids:
        vid = state.get("current_vertex_id")
        ready_ids = [vid] if vid else []

    if not ready_ids:
        return {"task_graph": graph.model_dump()}

    # If only one vertex → execute directly (no asyncio.gather overhead)
    if len(ready_ids) == 1:
        graph = await _execute_single_vertex(graph, ready_ids[0], state)
        return {"task_graph": graph.model_dump()}

    # Multiple ready vertices → execute in parallel with asyncio.gather
    # Each coroutine gets a deep-copy of the graph to avoid race conditions
    # on shared mutable state (vertices dict, edges, token counts).
    import copy

    async def _run(vid: str) -> TaskGraph:
        graph_copy = TaskGraph(**copy.deepcopy(graph.model_dump()))
        return await _execute_single_vertex(graph_copy, vid, state)

    results = await asyncio.gather(
        *[_run(vid) for vid in ready_ids],
        return_exceptions=True,
    )

    # Merge results back: copy each executed vertex + updated edges/vertices
    for i, res in enumerate(results):
        vid = ready_ids[i]
        if isinstance(res, Exception):
            logger.error("Parallel vertex %s failed: %s", vid, res)
            fail_vertex(graph, vid, str(res))
        elif isinstance(res, TaskGraph):
            # 1. Merge the executed vertex itself
            if vid in res.vertices:
                graph.vertices[vid] = res.vertices[vid]
            # 2. Merge outgoing edge payloads filled by complete_vertex
            for edge in res.edges:
                if edge.payload and edge.source_id == vid:
                    for orig_edge in graph.edges:
                        if orig_edge.id == edge.id:
                            orig_edge.payload = edge.payload
                            break
            # 3. Merge any new vertices/edges added by impact analysis
            for new_vid, new_v in res.vertices.items():
                if new_vid not in graph.vertices:
                    graph.vertices[new_vid] = new_v
            existing_edge_ids = {e.id for e in graph.edges}
            for new_edge in res.edges:
                if new_edge.id not in existing_edge_ids:
                    graph.edges.append(new_edge)
            # 4. Accumulate token/LLM counts
            src_v = res.vertices.get(vid)
            if src_v:
                graph.total_token_count += src_v.token_count
                graph.total_llm_calls += src_v.llm_calls

    # Recalculate downstream readiness after all merges are done
    from app.graph_agent.graph import get_outgoing_edges
    for vid in ready_ids:
        for edge in get_outgoing_edges(graph, vid):
            target = graph.vertices.get(edge.target_id)
            if target and target.status == VertexStatus.PENDING:
                # Check if all incoming edges now have payloads
                incoming = [e for e in graph.edges if e.target_id == edge.target_id
                            and e.edge_type == EdgeType.DEPENDENCY]
                if all(e.payload is not None for e in incoming):
                    target.status = VertexStatus.READY

    return {"task_graph": graph.model_dump()}


async def _execute_single_vertex(
    graph: TaskGraph,
    vertex_id: str,
    state: dict,
) -> TaskGraph:
    """Execute a single vertex (shared logic for serial and parallel paths)."""
    vertex = start_vertex(graph, vertex_id)
    if not vertex:
        return graph

    await report_vertex_started(graph, vertex_id)

    try:
        # PLANNER/DECOMPOSE → recursive decomposition (creates sub-graph)
        if vertex.vertex_type in (VertexType.PLANNER, VertexType.DECOMPOSE):
            graph = await asyncio.wait_for(
                _handle_decompose_vertex(graph, vertex, state),
                timeout=_VERTEX_OVERALL_TIMEOUT_S,
            )
        else:
            # All other types → agentic tool loop (with overall timeout)
            context = _build_context(vertex)
            result, summary = await asyncio.wait_for(
                _dispatch_vertex_handler(vertex, context, state),
                timeout=_VERTEX_OVERALL_TIMEOUT_S,
            )

            # Complete vertex — fills outgoing edge payloads
            complete_vertex(
                graph, vertex_id,
                result=result,
                result_summary=summary,
                local_context=result,
            )

    except asyncio.TimeoutError:
        logger.error("Vertex %s timed out after %ds", vertex_id, _VERTEX_OVERALL_TIMEOUT_S)
        fail_vertex(graph, vertex_id, f"Vertex timed out after {_VERTEX_OVERALL_TIMEOUT_S}s")
    except Exception as e:
        logger.error("Vertex %s failed: %s", vertex_id, e, exc_info=True)
        fail_vertex(graph, vertex_id, str(e))

    await report_vertex_completed(graph, vertex_id)

    # --- Impact analysis: propagate changes through artifact graph ---
    completed_vertex = graph.vertices.get(vertex_id)
    if (
        completed_vertex
        and completed_vertex.status == VertexStatus.COMPLETED
        and completed_vertex.vertex_type not in (VertexType.PLANNER, VertexType.DECOMPOSE)
        and settings.use_graph_agent
    ):
        try:
            new_ids = await analyze_impact(graph, completed_vertex, state)
            if new_ids:
                logger.info(
                    "Impact analysis created %d new vertices for vertex %s",
                    len(new_ids), vertex_id,
                )
        except Exception as e:
            logger.warning("Impact analysis failed for vertex %s: %s", vertex_id, e)

    await task_graph_store.save(graph)

    return graph


# ---------------------------------------------------------------------------
# Node: synthesize — compose final result
# ---------------------------------------------------------------------------


async def node_synthesize(state: GraphAgentState) -> dict:
    """Compose the final result from completed vertices using LLM synthesis."""
    graph_data = state.get("task_graph")
    if not graph_data:
        return {"final_result": state.get("graph_error", "No graph available")}

    graph = TaskGraph(**graph_data)
    graph.status = GraphStatus.COMPLETED
    graph.completed_at = str(int(time.time()))

    raw_result = get_final_result(graph)
    stats = get_stats(graph)

    # LLM synthesis — combine vertex results intelligently
    if raw_result and len(graph.vertices) > 2:
        try:
            task_data = state.get("task", {})
            original_request = task_data.get("message", "")
            response = await llm_with_cloud_fallback(
                state=state,
                messages=[
                    {
                        "role": "system",
                        "content": (
                            "You are a synthesis agent. Combine the results from multiple "
                            "sub-tasks into a coherent, well-structured final answer. "
                            "Remove redundancy, resolve conflicts, and present a unified response. "
                            "Use the user's language."
                        ),
                    },
                    {
                        "role": "user",
                        "content": (
                            f"## Original request\n{original_request}\n\n"
                            f"## Sub-task results\n{raw_result}\n\n"
                            "Synthesize these results into a coherent final answer."
                        ),
                    },
                ],
                task_type="graph_synthesis",
                max_tokens=settings.default_output_tokens,
            )
            result = response.choices[0].message.content or raw_result
        except Exception as e:
            logger.warning("LLM synthesis failed, using concatenation: %s", e)
            result = raw_result
    else:
        result = raw_result

    await task_graph_store.save(graph)
    await report_graph_status(graph, "Graph execution completed")

    logger.info(
        "Graph agent done: vertices=%d tokens=%d",
        stats["total_vertices"], stats["total_tokens"],
    )

    return {"final_result": result, "task_graph": graph.model_dump()}


# ---------------------------------------------------------------------------
# Routing
# ---------------------------------------------------------------------------


def route_after_select(state: GraphAgentState) -> str:
    """Route after select_next: dispatch if vertex found, synthesize if done."""
    if state.get("graph_error"):
        return "synthesize"
    if state.get("current_vertex_id"):
        return "dispatch_vertex"
    return "synthesize"


def route_after_dispatch(state: GraphAgentState) -> str:
    """After dispatching a vertex, always go back to select_next."""
    return "select_next"


# ---------------------------------------------------------------------------
# Graph builder
# ---------------------------------------------------------------------------


def build_graph_agent_graph() -> StateGraph:
    """Build the LangGraph StateGraph for Graph Agent execution.

    Flow:
        decompose → select_next → dispatch_vertex → select_next → ... → synthesize → END
    """
    graph = StateGraph(GraphAgentState)

    graph.add_node("decompose", node_decompose)
    graph.add_node("select_next", node_select_next)
    graph.add_node("dispatch_vertex", node_dispatch_vertex)
    graph.add_node("synthesize", node_synthesize)

    graph.set_entry_point("decompose")
    graph.add_edge("decompose", "select_next")
    graph.add_conditional_edges("select_next", route_after_select)
    graph.add_conditional_edges("dispatch_vertex", route_after_dispatch)
    graph.add_edge("synthesize", END)

    return graph


# ---------------------------------------------------------------------------
# Entry point (called from orchestrator.py)
# ---------------------------------------------------------------------------


# Cached compiled graph
_compiled_graph = None
_checkpointer: MongoDBSaver | None = None


async def init_graph_agent_checkpointer() -> None:
    """Initialize MongoDB checkpointer for Graph Agent."""
    global _checkpointer, _compiled_graph
    client = MongoClient(settings.mongodb_url)
    _checkpointer = MongoDBSaver(client, db_name="jervis_graph_agent")
    _compiled_graph = None
    logger.info("Graph Agent LangGraph checkpointer initialized")


def _get_compiled_graph():
    """Get or build the compiled LangGraph graph."""
    global _compiled_graph
    if _compiled_graph is None:
        if _checkpointer is None:
            raise RuntimeError("Graph Agent checkpointer not initialized")
        sg = build_graph_agent_graph()
        _compiled_graph = sg.compile(checkpointer=_checkpointer)
    return _compiled_graph


async def _fetch_resource_context(client_id: str, project_id: str | None) -> str:
    """Fetch existing clients, projects, connections for decomposer context.

    Returns a formatted string describing available resources so the decomposer
    can make informed decisions about what already exists vs. what needs creation.
    """
    import httpx
    from app.config import settings

    parts: list[str] = []
    base = settings.kotlin_server_url
    if not base:
        return ""

    try:
        async with httpx.AsyncClient(timeout=10) as http:
            # Fetch clients — filter by clientId (tenant isolation)
            params = {"clientId": client_id} if client_id else {}
            resp = await http.get(f"{base}/internal/clients", params=params)
            if resp.status_code == 200:
                clients = resp.json()
                if clients:
                    parts.append(f"Existing clients ({len(clients)}):")
                    for c in clients[:10]:
                        parts.append(f"  - {c.get('name', '?')} (id={c.get('id', '?')})")

            # Fetch projects for this client
            resp = await http.get(f"{base}/internal/projects", params=params)
            if resp.status_code == 200:
                projects = resp.json()
                if projects:
                    parts.append(f"Existing projects ({len(projects)}):")
                    for p in projects[:10]:
                        parts.append(f"  - {p.get('name', '?')} (id={p.get('id', '?')})")

            # Fetch connections — filter by clientId (tenant isolation)
            resp = await http.get(f"{base}/internal/connections", params=params)
            if resp.status_code == 200:
                conns = resp.json()
                if conns:
                    parts.append(f"Available connections ({len(conns)}):")
                    for c in conns[:10]:
                        caps = c.get("capabilities", [])
                        parts.append(
                            f"  - {c.get('name', '?')} ({c.get('provider', '?')}) "
                            f"caps={caps}"
                        )
    except Exception as e:
        logger.debug("Failed to fetch resource context: %s", e)

    return "\n".join(parts)


async def run_graph_agent(
    request: OrchestrateRequest,
    thread_id: str = "default",
) -> dict:
    """Run the Graph Agent via LangGraph.

    Called from run_orchestration() when use_graph_agent is True.
    Returns the final LangGraph state dict.
    """
    compiled = _get_compiled_graph()
    config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 200}

    initial_state: GraphAgentState = {
        "task": CodingTask(
            id=request.task_id,
            client_id=request.client_id,
            project_id=request.project_id,
            client_name=request.client_name,
            project_name=request.project_name,
            workspace_path=request.workspace_path,
            query=request.query,
            agent_preference=request.agent_preference,
        ).model_dump(),
        "rules": request.rules.model_dump(),
        "environment": request.environment,
        "chat_history": request.chat_history.model_dump() if request.chat_history else None,
        "processing_mode": request.processing_mode,
        "response_language": "en",
        "allow_cloud_prompt": False,
        "task_graph": None,
        "current_vertex_id": None,
        "graph_error": None,
        "final_result": None,
    }

    final_state = await compiled.ainvoke(initial_state, config=config)
    return final_state


# ---------------------------------------------------------------------------
# Vertex type handlers (each = a distinct responsibility)
# ---------------------------------------------------------------------------

# System prompts per vertex type
_SYSTEM_PROMPTS: dict[VertexType, str] = {
    VertexType.PLANNER: (
        "You are the Planner. Analyze the task and create a structured plan. "
        "Break complex tasks into clear steps. Output a numbered plan. "
        "Use the provided tools to gather information about the codebase, "
        "knowledge base, and project structure as needed."
    ),
    VertexType.DECOMPOSE: (
        "You are the Planner. Analyze the task and create a structured plan. "
        "Break complex tasks into clear steps. Output a numbered plan. "
        "Use the provided tools to gather information as needed."
    ),
    VertexType.INVESTIGATOR: (
        "You are the Investigator. Research the topic thoroughly. "
        "Use the provided tools to search the knowledge base, web, "
        "codebase, and repository. Find relevant information, identify "
        "gaps, and compile findings. Be precise and cite sources."
    ),
    VertexType.EXECUTOR: (
        "You are the Executor. Complete the assigned task using the provided "
        "context and tools. Be thorough, precise, and produce actionable "
        "output. Use tools for coding, KB operations, and other concrete work.\n\n"
        "## Knowledge Persistence\n"
        "When discussing or refining requirements with the user:\n"
        "- After each confirmed decision or requirement, call `store_knowledge` "
        "with category 'specification' to persist it in KB\n"
        "- Use clear subjects like 'Platform decision', 'Storage choice', "
        "'Feature: user auth', 'API: book database'\n"
        "- This ensures nothing is lost even if the conversation spans hours/days\n"
        "- When the user mentions another project (e.g., 'this would work in XYZ'), "
        "use target_project_name to cross-reference\n"
        "Store the decision with enough detail to reconstruct it later — "
        "not just keywords, but the reasoning and context behind the decision."
    ),
    VertexType.TASK: (
        "You are the Executor. Complete the assigned task using the provided "
        "context and tools. Be thorough, precise, and produce actionable output.\n\n"
        "## Knowledge Persistence\n"
        "When discussing or refining requirements with the user:\n"
        "- After each confirmed decision, call `store_knowledge` with category "
        "'specification' to persist it in KB with enough detail to reconstruct later\n"
        "- When the user mentions another project, use target_project_name to cross-reference"
    ),
    VertexType.VALIDATOR: (
        "You are the Validator. Verify the upstream results for correctness, "
        "completeness, and quality. Use tools to check the codebase, branches, "
        "and recent commits. Report any issues found. "
        "Respond with: PASS (all good) or FAIL (with details)."
    ),
    VertexType.REVIEWER: (
        "You are the Reviewer. Review the upstream work for quality, "
        "best practices, and potential improvements. Use tools to inspect "
        "the codebase and verify claims. Provide constructive feedback. "
        "Rate: APPROVED, NEEDS_CHANGES, or REJECTED."
    ),
    VertexType.SYNTHESIS: (
        "You are the Synthesizer. Combine multiple upstream results into a "
        "coherent, unified response. Preserve key details, resolve "
        "contradictions, and acknowledge any failures."
    ),
    VertexType.GATE: (
        "You are the Gate. Evaluate upstream results and decide whether to "
        'proceed. Respond with JSON: {"proceed": true/false, "reason": "..."}'
    ),
    VertexType.SETUP: (
        "You are the Setup Agent — responsible for project scaffolding, technology "
        "decisions, and environment provisioning.\n\n"
        "## Requirement Reconstruction\n"
        "Requirements have been accumulated across multiple conversation messages "
        "and stored progressively in KB (category 'specification'). You MUST:\n"
        "1. First call `kb_search` with queries like 'specification', 'requirement', "
        "'platform decision', 'feature' to find ALL accumulated specification entries\n"
        "2. Read the upstream context — it contains the memories summary\n"
        "3. Combine KB results + upstream context into a COMPLETE requirements brief\n"
        "4. If any detail seems missing, search KB with more specific queries\n"
        "Nothing from the discussion should be lost — every decision was stored in KB.\n\n"
        "## Workflow\n"
        "1. **Reconstruct requirements**: Search KB for all specification entries. "
        "Combine with upstream context to build the complete requirements brief.\n"
        "2. **Get recommendations**: Call `get_stack_recommendations` with the FULL "
        "reconstructed requirements text.\n"
        "3. **Present to user**: Use `ask_user` to present the recommendations and ask "
        "the user to confirm or adjust choices. Format clearly with pros/cons.\n"
        "4. **Create infrastructure**: Based on confirmed choices:\n"
        "   - `create_client` (if no client exists)\n"
        "   - `create_project` (under the client)\n"
        "   - `create_connection` (for Git hosting)\n"
        "   - `create_git_repository` (GitHub/GitLab repo)\n"
        "   - `update_project` (link the git remote URL)\n"
        "5. **Scaffold code**: Use `dispatch_coding_agent` with the scaffolding "
        "instructions from the recommendations. The coding agent will generate the "
        "actual project structure, build files, and boilerplate.\n"
        "6. **Provision environment**: Use environment tools (environment_create, "
        "environment_add_component, environment_deploy) to create a dev environment.\n"
        "7. **Initialize workspace**: Call `init_workspace` to clone the repo.\n\n"
        "IMPORTANT: Do NOT generate code yourself. Always use `dispatch_coding_agent` "
        "for code generation — it handles Git branches, commits, and PR creation.\n"
        "IMPORTANT: Always confirm technology choices with the user via `ask_user` "
        "BEFORE creating any infrastructure or dispatching coding agent."
    ),
}

# Max agentic loop iterations per vertex (tool call rounds)
_MAX_VERTEX_TOOL_ITERATIONS = 12
# Timeout for a single tool execution within vertex loop
_VERTEX_TOOL_TIMEOUT_S = 60
# Overall timeout for an entire vertex execution (all iterations combined)
_VERTEX_OVERALL_TIMEOUT_S = 600


# ---------------------------------------------------------------------------
# Agentic tool loop — core execution engine for each vertex
# ---------------------------------------------------------------------------


async def _agentic_vertex(
    vertex: GraphVertex,
    context: str,
    state: dict,
) -> tuple[str, str]:
    """Execute a vertex with an agentic tool loop.

    1. Load default tools for the vertex type
    2. Call LLM with tools
    3. If LLM returns tool calls → execute them → append results → repeat
    4. If LLM returns text (no tool calls) → that's the final result
    5. Handle `request_tools` meta-tool by adding requested categories

    Returns (result, summary).
    """
    # --- Specialist agent shortcut for EXECUTOR/TASK ---
    if (
        vertex.vertex_type in (VertexType.EXECUTOR, VertexType.TASK)
        and vertex.agent_name
        and settings.use_specialist_agents
    ):
        agent_result = await _try_specialist_agent(vertex, context, state)
        if agent_result is not None:
            return agent_result

    # --- Build system prompt ---
    system_prompt = _SYSTEM_PROMPTS.get(
        vertex.vertex_type,
        _SYSTEM_PROMPTS[VertexType.EXECUTOR],
    )

    # --- Load default tools ---
    tools = get_default_tools(vertex.vertex_type)

    # --- Build initial messages ---
    user_content = (
        f"## {vertex.title}\n\n{vertex.description}\n\n"
        f"## Context\n{context}"
    )

    messages: list[dict] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_content},
    ]

    # --- Extract IDs for tool execution ---
    task_data = state.get("task", {})
    client_id = task_data.get("client_id", "")
    project_id = task_data.get("project_id")

    # --- Agentic loop ---
    tool_call_history: list[tuple[str, str]] = []
    iteration = 0
    result = ""

    while iteration < _MAX_VERTEX_TOOL_ITERATIONS:
        iteration += 1

        # --- Cancellation check: bail out if graph was cancelled externally ---
        graph_data = state.get("task_graph")
        if graph_data:
            live_graph = TaskGraph(**graph_data) if isinstance(graph_data, dict) else graph_data
            if live_graph.status == GraphStatus.CANCELLED:
                logger.info("Vertex %s: graph cancelled, aborting agentic loop", vertex.id)
                vertex.status = VertexStatus.CANCELLED
                return ("Cancelled by user.", "Cancelled")

        response = await llm_with_cloud_fallback(
            state=state,
            messages=messages,
            task_type="graph_vertex",
            max_tokens=settings.default_output_tokens,
            tools=tools if tools else None,
        )

        message = response.choices[0].message
        vertex.llm_calls += 1
        vertex.token_count += getattr(response, "usage", None) and response.usage.total_tokens or 0

        # Extract tool calls (handles both native and Ollama formats)
        tool_calls, remaining_text = extract_tool_calls(message)

        # No tool calls → final answer
        if not tool_calls:
            result = remaining_text or message.content or ""
            break

        # Append assistant message with tool calls to history
        _append_assistant_message(messages, message, tool_calls)

        # Execute each tool call
        for tc in tool_calls:
            tool_name = tc.function.name
            try:
                arguments = json.loads(tc.function.arguments)
            except (json.JSONDecodeError, TypeError):
                arguments = {}

            # Handle the request_tools meta-tool
            if tool_name == "request_tools":
                tool_result = _handle_request_tools(arguments, tools, vertex)
                messages.append({
                    "role": "tool",
                    "tool_call_id": tc.id,
                    "name": tool_name,
                    "content": tool_result,
                })
                continue

            # Loop detection
            loop_reason = detect_tool_loop(
                tool_call_history, tool_name, arguments,
            )
            if loop_reason:
                messages.append({
                    "role": "tool",
                    "tool_call_id": tc.id,
                    "name": tool_name,
                    "content": f"ERROR: {loop_reason}",
                })
                continue

            # Execute the tool
            vertex.tools_used.append(tool_name)
            try:
                tool_result = await asyncio.wait_for(
                    execute_tool(
                        tool_name=tool_name,
                        arguments=arguments,
                        client_id=client_id,
                        project_id=project_id,
                        processing_mode=state.get("processing_mode", "BACKGROUND"),
                        skip_approval=True,
                    ),
                    timeout=_VERTEX_TOOL_TIMEOUT_S,
                )
            except asyncio.TimeoutError:
                tool_result = f"Error: Tool '{tool_name}' timed out after {_VERTEX_TOOL_TIMEOUT_S}s"
            except AskUserInterrupt as e:
                # Agent wants to ask user — interrupt graph execution
                logger.info("VERTEX %s: ask_user interrupt: %s", vertex.id, e.question)
                user_response = interrupt({
                    "type": "clarification_request",
                    "action": "clarify",
                    "description": e.question,
                })
                if isinstance(user_response, dict):
                    tool_result = f"User answered: {user_response.get('reason', str(user_response))}"
                else:
                    tool_result = f"User answered: {user_response}"
            except Exception as e:
                tool_result = f"Error executing {tool_name}: {e}"

            messages.append({
                "role": "tool",
                "tool_call_id": tc.id,
                "name": tool_name,
                "content": str(tool_result),
            })
    else:
        # Max iterations reached — use whatever we have
        logger.warning(
            "Vertex %s hit max tool iterations (%d)",
            vertex.id, _MAX_VERTEX_TOOL_ITERATIONS,
        )
        if not result:
            result = remaining_text or "(max tool iterations reached)"

    summary = result[:500]
    return result, summary


async def _try_specialist_agent(
    vertex: GraphVertex,
    context: str,
    state: dict,
) -> tuple[str, str] | None:
    """Try dispatching to a specialist agent. Returns None if unavailable."""
    try:
        from app.agents.registry import AgentRegistry
        registry = AgentRegistry.instance()
        agent = registry.get(vertex.agent_name)
        if agent:
            msg = DelegationMessage(
                delegation_id=vertex.id,
                depth=vertex.depth,
                agent_name=vertex.agent_name,
                task_summary=vertex.description,
                context=context,
                expected_output="Complete the task",
                response_language=state.get("response_language", "en"),
                client_id=state.get("task", {}).get("client_id", ""),
                project_id=state.get("task", {}).get("project_id"),
            )
            output = await agent.execute(msg, state)
            return output.result, output.result[:500]
    except Exception as e:
        logger.warning("Agent dispatch failed, falling back to LLM: %s", e)
    return None


def _handle_request_tools(
    arguments: dict,
    current_tools: list[dict],
    vertex: GraphVertex,
) -> str:
    """Handle the `request_tools` meta-tool call.

    Adds requested tool categories to the current tool set (mutates in place).
    Returns a confirmation message for the LLM.
    """
    categories = arguments.get("categories", [])
    reason = arguments.get("reason", "")
    added: list[str] = []
    existing_names = {
        t.get("function", {}).get("name") for t in current_tools
    }

    for cat in categories:
        cat_tools = get_tools_by_category(cat)
        for tool in cat_tools:
            name = tool.get("function", {}).get("name")
            if name and name not in existing_names:
                current_tools.append(tool)
                existing_names.add(name)
                added.append(name)

    logger.info(
        "Vertex %s requested tools: categories=%s reason=%s added=%s",
        vertex.id, categories, reason, added,
    )

    if added:
        return f"Added {len(added)} tools: {', '.join(added)}. You can now use them."
    return "No new tools added (all requested tools were already available)."


def _append_assistant_message(
    messages: list[dict],
    message,
    tool_calls: list,
) -> None:
    """Append the assistant's message (with tool calls) to message history."""
    tc_list = []
    for tc in tool_calls:
        tc_list.append({
            "id": tc.id,
            "type": "function",
            "function": {
                "name": tc.function.name,
                "arguments": tc.function.arguments,
            },
        })

    content = getattr(message, "content", None) or ""
    messages.append({
        "role": "assistant",
        "content": content,
        "tool_calls": tc_list,
    })


async def _dispatch_vertex_handler(
    vertex: GraphVertex,
    context: str,
    state: dict,
) -> tuple[str, str]:
    """Unified dispatch — all vertex types go through the agentic loop."""
    return await _agentic_vertex(vertex, context, state)


async def _handle_decompose_vertex(
    graph: TaskGraph,
    vertex: GraphVertex,
    state: dict,
) -> TaskGraph:
    """Handle PLANNER/DECOMPOSE vertex — recursive decomposition.

    Instead of executing the vertex via LLM tool loop, calls decompose_vertex()
    to create sub-vertices + edges in the graph. The vertex itself is marked
    COMPLETED and its children will be picked up by subsequent select_next cycles.

    If decomposition fails or hits depth/count limits, the vertex is converted
    to EXECUTOR and falls through to the agentic tool loop on next dispatch.
    """
    from app.graph_agent.decomposer import MAX_DECOMPOSE_DEPTH, MAX_TOTAL_VERTICES

    vertex_id = vertex.id

    # Check limits — if exceeded, convert to EXECUTOR and let agentic loop handle it
    if vertex.depth >= MAX_DECOMPOSE_DEPTH:
        logger.info(
            "Vertex %s at depth %d — max depth reached, converting to EXECUTOR",
            vertex_id, vertex.depth,
        )
        vertex.vertex_type = VertexType.EXECUTOR
        context = _build_context(vertex)
        result, summary = await _agentic_vertex(vertex, context, state)
        complete_vertex(graph, vertex_id, result=result, result_summary=summary, local_context=result)
        return graph

    if len(graph.vertices) >= MAX_TOTAL_VERTICES:
        logger.info(
            "Graph has %d vertices — max reached, converting %s to EXECUTOR",
            len(graph.vertices), vertex_id,
        )
        vertex.vertex_type = VertexType.EXECUTOR
        context = _build_context(vertex)
        result, summary = await _agentic_vertex(vertex, context, state)
        complete_vertex(graph, vertex_id, result=result, result_summary=summary, local_context=result)
        return graph

    # Recursive decomposition — creates sub-vertices + edges
    try:
        guidelines = state.get("rules", {}).get("guidelines_text", "")
        graph = await decompose_vertex(
            graph=graph,
            vertex_id=vertex_id,
            state=state,
            guidelines=guidelines,
        )
        logger.info(
            "Decomposed vertex %s (depth=%d) — graph now has %d vertices",
            vertex_id, vertex.depth, len(graph.vertices),
        )
    except Exception as e:
        # Decomposition failed — convert to EXECUTOR as fallback
        logger.warning(
            "Decomposition failed for vertex %s, falling back to EXECUTOR: %s",
            vertex_id, e,
        )
        vertex.vertex_type = VertexType.EXECUTOR
        context = _build_context(vertex)
        result, summary = await _agentic_vertex(vertex, context, state)
        complete_vertex(graph, vertex_id, result=result, result_summary=summary, local_context=result)

    return graph


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _build_context(vertex: GraphVertex) -> str:
    """Build context string from vertex's incoming edge payloads."""
    if not vertex.incoming_context:
        return vertex.input_request

    parts = [f"## Task\n{vertex.input_request}"]
    for payload in vertex.incoming_context:
        parts.append(
            f"\n## From: {payload.source_vertex_title}\n"
            f"Summary: {payload.summary}\n"
            f"Context:\n{payload.context}"
        )
    return "\n".join(parts)

