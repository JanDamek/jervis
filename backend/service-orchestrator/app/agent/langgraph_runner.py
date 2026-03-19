"""LangGraph-based runner for Graph Agent (reactive/lazy progressive decomposition).

Execution model:
  1. Root vertex = the original request, type TASK, status READY
  2. Each vertex tries to solve directly (LLM + tools agentic loop)
  3. If too complex → vertex calls decompose_task tool → creates children → WAITING_CHILDREN
  4. Children execute recursively (same pattern)
  5. When ALL children complete → parent resumes with their summaries → evaluates → done
  6. Simple questions = 1 vertex, direct answer, no decomposition at all

The graph is a loop:
  init_root → select_next → dispatch_vertex → [loop back to select_next]
                                             → synthesize (when all done)

No upfront decomposition — the graph grows dynamically from actual needs.
AgentGraph is carried in LangGraph state — LangGraph handles checkpointing,
interrupt/resume, and execution flow.
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
from langgraph.errors import GraphInterrupt
from langgraph.types import interrupt

from app.config import settings
from app.graph.nodes._helpers import (
    detect_tool_loop,
    llm_with_cloud_fallback,
    parse_json_response,
)
from app.agent.decomposer import create_child_vertices, _format_evidence
from app.agent.graph import (
    block_vertex,
    complete_vertex,
    fail_vertex,
    find_blocked_vertices,
    get_children,
    get_ready_vertices,
    resume_vertex,
    start_vertex,
    get_final_result,
    get_stats,
)
from app.agent.models import (
    EdgeType,
    GraphStatus,
    GraphVertex,
    AgentGraph,
    VertexStatus,
    VertexType,
)
from app.agent.graph import create_task_graph
from app.agent.persistence import agent_store
from app.agent.progress import (
    report_decomposition_progress,
    report_graph_status,
    report_vertex_completed,
    report_vertex_started,
)
from app.agent.impact import analyze_impact
from app.agent.tool_sets import get_default_tools, get_tools_by_category
from app.agent.validation import validate_graph
from app.models import ChatHistoryPayload, CodingTask, DelegationMessage, OrchestrateRequest
from app.tools.executor import AskUserInterrupt, execute_tool
from app.tools.ollama_parsing import extract_tool_calls

logger = logging.getLogger(__name__)


class DecomposeInterrupt(Exception):
    """Raised when a vertex decides to decompose via the decompose_task tool.

    This interrupts the agentic tool loop — the vertex transitions to
    WAITING_CHILDREN and will be resumed after all children complete.
    """
    pass


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
    task_graph: dict | None             # AgentGraph serialized
    current_vertex_id: str | None       # Vertex being processed
    ready_vertex_ids: list[str]         # All READY vertices for parallel execution
    graph_error: str | None             # Error if graph-level failure
    final_result: str | None            # Composed final result


# ---------------------------------------------------------------------------
# Node: decompose
# ---------------------------------------------------------------------------


async def node_decompose(state: GraphAgentState) -> dict:
    """Initialize the graph with a root TASK vertex (no upfront LLM decomposition).

    Reactive model: the root vertex will try to solve directly. If it needs
    sub-tasks, it calls decompose_task during its agentic tool loop.

    Pre-built thinking graphs (from chat dispatch or pod restart) are handled
    as before — skip initialization, reuse existing graph.
    """
    # Pre-built thinking graph — skip initialization, graph already has vertices
    if state.get("task_graph"):
        graph = AgentGraph(**state["task_graph"])
        agent_store.cache_subgraph(graph)
        agent_store.mark_dirty(graph.task_id)
        logger.info(
            "Skipping init — pre-built thinking graph with %d vertices",
            len(graph.vertices),
        )
        await report_graph_status(graph, f"Pre-built map with {len(graph.vertices) - 1} vertices")
        return {"current_vertex_id": None, "graph_error": None}

    if "task" not in state:
        return {"graph_error": "Missing task context — cannot initialize (stale checkpoint?)"}
    task = CodingTask(**state["task"])

    # Create graph with root vertex (type=TASK, status=READY)
    # Root vertex IS the task — it will try direct resolution first
    graph = create_task_graph(
        task_id=task.id,
        client_id=task.client_id,
        project_id=task.project_id,
        root_title=task.query[:100],
        root_description=task.query,
    )

    # Override root vertex type: TASK instead of ROOT (it will execute, not just decompose)
    root = graph.vertices[graph.root_vertex_id]
    root.vertex_type = VertexType.TASK

    # Build evidence context for the root vertex description
    evidence = state.get("evidence_pack") or {}
    resource_context = await _fetch_resource_context(task.client_id, task.project_id)
    if resource_context:
        evidence["existing_resources"] = resource_context

    # Include chat history
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

    # Enrich root vertex description with evidence (so the LLM has context)
    if evidence:
        evidence_text = _format_evidence(evidence)
        if evidence_text and evidence_text != "(no evidence available)":
            root.description = f"{root.description}\n\n## Context\n{evidence_text}"
            root.input_request = root.description

    # Graph is immediately READY — root vertex will execute directly
    graph.status = GraphStatus.READY

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

    # Cache in RAM + mark dirty for async DB flush
    agent_store.cache_subgraph(graph)
    agent_store.mark_dirty(graph.task_id)
    await report_graph_status(graph, "Root vertex ready — trying direct resolution")

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

    Transient error retry: when no READY vertices remain but some FAILED
    vertices have transient errors (rate limit, 503, timeout), reset them
    to READY for one more attempt (max 2 retries per vertex).
    """
    graph_data = state.get("task_graph")
    if not graph_data:
        return {"current_vertex_id": None}

    graph = AgentGraph(**graph_data)

    # Stop scheduling if graph is cancelled (but NOT failed — retry may fix it)
    if graph.status == GraphStatus.CANCELLED:
        logger.info("Graph %s cancelled — stopping vertex scheduling", graph.id)
        return {"task_graph": graph.model_dump(), "current_vertex_id": None}

    ready = get_ready_vertices(graph)

    if not ready:
        # Before giving up, check for transient failures that can be retried
        retried = _retry_transient_failures(graph)
        if retried:
            ready = get_ready_vertices(graph)
            logger.info(
                "SELECT_NEXT | graph=%s | retried %d transient failures → %d now ready",
                graph.id, retried, len(ready),
            )
            # Clear graph-level FAILED status so scheduling continues
            if graph.status == GraphStatus.FAILED:
                graph.status = GraphStatus.EXECUTING

    if not ready:
        # Log status summary for debugging stalled graphs
        statuses = {}
        for v in graph.vertices.values():
            statuses[v.status.value] = statuses.get(v.status.value, 0) + 1
        logger.info(
            "SELECT_NEXT | graph=%s | no_ready | statuses=%s | graph_status=%s",
            graph.id, statuses, graph.status.value,
        )
        return {
            "task_graph": graph.model_dump(),
            "current_vertex_id": None,
            "ready_vertex_ids": [],
        }

    # Return all ready vertices for parallel execution
    ready_ids = [v.id for v in ready]
    ready_titles = [v.title for v in ready]
    logger.info(
        "SELECT_NEXT | graph=%s | ready=%d | titles=[%s]",
        graph.id, len(ready_ids), ", ".join(ready_titles[:5]),
    )
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
    graph = AgentGraph(**state["task_graph"])
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

    async def _run(vid: str) -> AgentGraph:
        graph_copy = AgentGraph(**copy.deepcopy(graph.model_dump()))
        return await _execute_single_vertex(graph_copy, vid, state)

    results = await asyncio.gather(
        *[_run(vid) for vid in ready_ids],
        return_exceptions=True,
    )

    # Merge results back: copy each executed vertex + updated edges/vertices
    for i, res in enumerate(results):
        vid = ready_ids[i]
        if isinstance(res, GraphInterrupt):
            # ASK_USER interrupt — mark vertex BLOCKED (not FAILED)
            logger.info("Parallel vertex %s blocked (ASK_USER)", vid)
            block_vertex(graph, vid, "Waiting for user input")
        elif isinstance(res, Exception):
            logger.error("Parallel vertex %s failed: %s", vid, res)
            fail_vertex(graph, vid, str(res))
        elif isinstance(res, AgentGraph):
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
    from app.agent.graph import get_outgoing_edges
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
    graph: AgentGraph,
    vertex_id: str,
    state: dict,
) -> AgentGraph:
    """Execute a single vertex (shared logic for serial and parallel paths).

    Handles two cases:
    1. Normal execution: vertex tries direct resolution via agentic tool loop
    2. Resumed from WAITING_CHILDREN: children done, parent evaluates results
    """
    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        return graph

    is_resumed = vertex.status == VertexStatus.WAITING_CHILDREN

    if is_resumed:
        # Resumed — children are done, their summaries are in incoming_context
        # (set by resume_parent_after_children in get_ready_vertices)
        vertex.status = VertexStatus.RUNNING
        vertex.started_at = vertex.started_at  # Keep original start time
        graph.status = GraphStatus.EXECUTING
    else:
        vertex = start_vertex(graph, vertex_id)
        if not vertex:
            return graph

    # Immediately flush RUNNING status to DB so UI sees "Probíhá" right away
    agent_store.cache_subgraph(graph)
    await agent_store.save(graph)

    await report_vertex_started(graph, vertex_id)

    try:
        # ALL vertex types go through the same agentic tool loop
        # (no special PLANNER/DECOMPOSE handling — decompose_task tool is available to all)
        context = _build_context(vertex)

        if is_resumed:
            # Build context with children results for evaluation
            context = _build_context_with_children_results(vertex, context)

        result, summary = await _dispatch_vertex_handler(vertex, context, state, graph=graph)

        # Complete vertex — fills outgoing edge payloads
        complete_vertex(
            graph, vertex_id,
            result=result,
            result_summary=summary,
            local_context=result,
        )

    except DecomposeInterrupt:
        # Vertex decided to decompose — children already created by the tool handler
        logger.info(
            "Vertex %s decomposed into children — WAITING_CHILDREN", vertex_id,
        )
        vertex.status = VertexStatus.WAITING_CHILDREN
        # Don't complete — wait for children to finish
        agent_store.cache_subgraph(graph)
        await agent_store.save(graph)

    except GraphInterrupt:
        # ASK_USER interrupt — mark vertex BLOCKED (not FAILED)
        logger.info("Vertex %s blocked (ASK_USER) — pausing for user input", vertex_id)
        block_vertex(graph, vertex_id, "Waiting for user input")
        agent_store.cache_subgraph(graph)
        await agent_store.save(graph)
    except Exception as e:
        logger.error("Vertex %s failed: %s", vertex_id, e, exc_info=True)
        fail_vertex(graph, vertex_id, str(e))

    await report_vertex_completed(graph, vertex_id)

    # --- Impact analysis: propagate changes through artifact graph ---
    completed_vertex = graph.vertices.get(vertex_id)
    if (
        completed_vertex
        and completed_vertex.status == VertexStatus.COMPLETED
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

    # Update RAM cache + mark dirty (periodic flush handles DB write)
    agent_store.cache_subgraph(graph)
    agent_store.mark_dirty(graph.task_id)

    return graph


# ---------------------------------------------------------------------------
# Node: synthesize — compose final result
# ---------------------------------------------------------------------------


async def node_synthesize(state: GraphAgentState) -> dict:
    """Compose the final result from the graph.

    In the reactive model, the root vertex IS the evaluator — its result
    is the final answer (it synthesizes children results as part of its
    resumed agentic loop). No separate synthesis LLM call needed for
    simple cases.

    For complex graphs with multiple terminal vertices, falls back to
    LLM synthesis.
    """
    graph_data = state.get("task_graph")
    if not graph_data:
        return {"final_result": state.get("graph_error", "No graph available")}

    graph = AgentGraph(**graph_data)
    has_blocked = any(v.status == VertexStatus.BLOCKED for v in graph.vertices.values())
    has_failures = any(v.status == VertexStatus.FAILED for v in graph.vertices.values())
    if has_blocked:
        graph.status = GraphStatus.BLOCKED
    elif has_failures:
        graph.status = GraphStatus.FAILED
    else:
        graph.status = GraphStatus.COMPLETED
    graph.completed_at = str(int(time.time()))

    # Always compute stats (needed for final logging)
    stats = get_stats(graph)

    # In reactive model, prefer root vertex result (it's the evaluator)
    root = graph.vertices.get(graph.root_vertex_id)
    if root and root.status == VertexStatus.COMPLETED and root.result:
        result = root.result
    else:
        # Fallback: collect from terminal vertices
        raw_result = get_final_result(graph)

        # LLM synthesis for complex graphs with multiple terminals
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

    # Compact completed vertices — keep result_summary, drop heavy fields
    # This reduces MongoDB document size significantly for large thinking graphs
    _compact_completed_graph(graph)

    # Final save — synchronous (graph is complete, flush immediately)
    await agent_store.save(graph)
    # Don't remove from RAM cache immediately — keep for 1h (debug visibility)
    # cleanup_thinking_graphs() in persistence.py handles RAM eviction
    await report_graph_status(graph, "Graph execution completed")

    vs = stats.get("vertex_statuses", {})
    logger.info(
        "SYNTHESIZE_DONE | graph=%s | status=%s | vertices=%d | "
        "completed=%d | failed=%d | skipped=%d | tokens=%d | llm_calls=%d | result_len=%d",
        graph.id, graph.status.value, stats["total_vertices"],
        vs.get("completed", 0), vs.get("failed", 0), vs.get("skipped", 0),
        stats["total_tokens"], stats["total_llm_calls"],
        len(result) if result else 0,
    )

    return {"final_result": result, "task_graph": graph.model_dump()}


# ---------------------------------------------------------------------------
# Graph compaction — reduce memory footprint of completed thinking graphs
# ---------------------------------------------------------------------------


def _compact_completed_graph(graph: AgentGraph) -> None:
    """Strip heavy fields from completed/failed vertices.

    Keeps: title, vertex_type, status, result_summary, error, tools_used,
           started_at, completed_at, client_id, project_id, parent_id, depth.
    Drops: result (full text), agent_messages (LLM history), incoming_context,
           local_context, input_request (preserved in result_summary).
    """
    for v in graph.vertices.values():
        if v.status not in (VertexStatus.COMPLETED, VertexStatus.FAILED, VertexStatus.SKIPPED):
            continue
        # Keep result_summary as the compressed representation
        v.result = ""
        v.local_context = ""
        v.agent_messages = []
        v.incoming_context = []
        # input_request is useful for understanding what was asked — keep it


# ---------------------------------------------------------------------------
# Transient failure retry
# ---------------------------------------------------------------------------

_TRANSIENT_PATTERNS = [
    "ratelimit", "rate limit", "rate_limit",
    "429", "503",
    "service unavailable", "internal server error",
    "timeout", "tokentimeouterror",
    "connection", "connectionerror",
]
_MAX_VERTEX_RETRIES = 2  # Max retries per vertex


def _retry_transient_failures(graph: AgentGraph) -> int:
    """Reset FAILED vertices with transient errors back to READY.

    Only retries vertices that:
    - Have a transient error (rate limit, timeout, 503, connection)
    - Have not exceeded _MAX_VERTEX_RETRIES

    Clears error payload on outgoing edges so downstream vertices
    can be re-evaluated after retry succeeds.

    Returns number of vertices reset.
    """
    retried = 0
    for v in graph.vertices.values():
        if v.status != VertexStatus.FAILED:
            continue
        if v.retry_count >= _MAX_VERTEX_RETRIES:
            continue
        error_lower = (v.error or "").lower()
        if not any(p in error_lower for p in _TRANSIENT_PATTERNS):
            continue

        # Reset vertex for retry
        prev_error = v.error
        v.status = VertexStatus.READY
        v.error = None
        v.retry_count += 1
        v.completed_at = None
        v.result = ""
        v.result_summary = ""

        # Clear error payloads on outgoing edges (will be re-filled on retry)
        from app.agent.graph import get_outgoing_edges
        for edge in get_outgoing_edges(graph, v.id):
            edge.payload = None

        # Reset downstream vertices that received error payload back to PENDING
        for edge in get_outgoing_edges(graph, v.id):
            target = graph.vertices.get(edge.target_id)
            if target and target.status in (VertexStatus.READY, VertexStatus.COMPLETED):
                # Only reset if it hasn't done meaningful work yet
                if target.status == VertexStatus.READY:
                    target.status = VertexStatus.PENDING

        logger.info(
            "RETRY_TRANSIENT | vertex=%s | title='%s' | retry=%d/%d | prev_error=%s",
            v.id, v.title, v.retry_count, _MAX_VERTEX_RETRIES,
            (prev_error or "")[:100],
        )
        retried += 1

    return retried


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
    """Initialize MongoDB checkpointer + AgentStore for Graph Agent."""
    global _checkpointer, _compiled_graph
    client = MongoClient(settings.mongodb_url)
    _checkpointer = MongoDBSaver(client, db_name="jervis")
    _compiled_graph = None

    # Initialize AgentStore (MongoDB + RAM cache + periodic flush)
    await agent_store.init()

    logger.info("Graph Agent LangGraph checkpointer + AgentStore initialized")


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


def _extract_user_response(query: str) -> str:
    """Extract [User response] text from task query/content.

    When a USER_TASK is answered, the Kotlin server appends
    '[User response]: <answer>' to the content. Extract that answer.
    """
    marker = "[User response]:"
    idx = query.rfind(marker)
    if idx < 0:
        return ""
    return query[idx + len(marker):].strip()


async def run_graph_agent(
    request: OrchestrateRequest,
    thread_id: str = "default",
) -> dict:
    """Run the Graph Agent via LangGraph.

    Called from run_orchestration(). Returns the final LangGraph state dict.
    """
    compiled = _get_compiled_graph()
    config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 200}

    # Check for pre-existing thinking graph (created by dispatch_map in chat,
    # OR previously EXECUTING graph that was interrupted by pod restart)
    existing_graph = await agent_store.load(request.task_id)
    pre_built_graph = None
    if existing_graph and existing_graph.status in (
        GraphStatus.READY, GraphStatus.BUILDING, GraphStatus.EXECUTING, GraphStatus.BLOCKED,
    ):
        # Resume from existing graph — handles both pre-built maps and pod restart recovery
        completed = sum(1 for v in existing_graph.vertices.values() if v.status == VertexStatus.COMPLETED)
        running = sum(1 for v in existing_graph.vertices.values() if v.status == VertexStatus.RUNNING)
        logger.info(
            "GRAPH_AGENT_RESUME | task=%s | status=%s | vertices=%d | completed=%d | running=%d",
            request.task_id, existing_graph.status.value,
            len(existing_graph.vertices), completed, running,
        )

        # Reset stale RUNNING vertices back to READY (pod restart killed their execution)
        # WAITING_CHILDREN vertices are preserved — their children may still be running
        for v in existing_graph.vertices.values():
            if v.status == VertexStatus.RUNNING:
                logger.info(
                    "GRAPH_AGENT_RESUME: resetting stale RUNNING vertex '%s' → READY",
                    v.title,
                )
                v.status = VertexStatus.READY
                v.agent_messages = []  # Clear stale LLM conversation
                v.agent_iteration = 0

        # Resume BLOCKED vertices if task contains [User response]
        # (USER_TASK → user answered → re-queued → graph agent re-invoked)
        blocked = find_blocked_vertices(existing_graph)
        if blocked:
            user_answer = _extract_user_response(request.query)
            if user_answer:
                for bv in blocked:
                    logger.info(
                        "GRAPH_AGENT_RESUME: resuming BLOCKED vertex '%s' with user answer",
                        bv.title,
                    )
                    resume_vertex(existing_graph, bv.id, user_answer)

        # For pre-built thinking graphs (old model): mark root as COMPLETED
        # For reactive model: root is a TASK vertex — don't force-complete it
        root = existing_graph.vertices.get(existing_graph.root_vertex_id)
        if root and root.vertex_type == VertexType.ROOT and root.status != VertexStatus.COMPLETED:
            complete_vertex(
                existing_graph, root.id,
                result="Pre-built thinking graph (decomposed by chat)",
                result_summary="Pre-built thinking graph",
            )
        existing_graph.status = GraphStatus.EXECUTING
        agent_store.cache_subgraph(existing_graph)
        pre_built_graph = existing_graph.model_dump()

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
        "task_graph": pre_built_graph,
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
_DECOMPOSE_HINT = (
    "\n\nIMPORTANT: Try to solve this task DIRECTLY first using available tools. "
    "Only use `decompose_task` if the task is genuinely too complex for a single agent pass "
    "(e.g., requires multiple independent investigations, parallel code changes, etc.). "
    "Simple questions should NEVER be decomposed."
)

_LANGUAGE_HINT = (
    "\n\nLANGUAGE: All user-facing output (summaries, reports, task titles, alerts) MUST be in Czech. "
    "Technical identifiers and code stay as-is. "
    "NEVER expose internal MongoDB ObjectIds as document/invoice numbers — "
    "describe items by their real content (sender, subject, filename)."
)

_SYSTEM_PROMPTS: dict[VertexType, str] = {
    VertexType.PLANNER: (
        "You are the Planner. Analyze the task and create a structured plan. "
        "Break it into clear, actionable steps. Use tools to gather information as needed."
        + _DECOMPOSE_HINT + _LANGUAGE_HINT
    ),
    VertexType.DECOMPOSE: (
        "You are the Planner. Analyze the task and create a structured plan. "
        "Break it into clear, actionable steps. Use tools to gather information as needed."
        + _DECOMPOSE_HINT + _LANGUAGE_HINT
    ),
    VertexType.INVESTIGATOR: (
        "You are the Investigator. Research the topic using the provided tools. "
        "Compile findings, identify gaps, and cite sources. "
        "Keep output concise — downstream vertices only see a brief summary.\n\n"
        "Store important findings via store_knowledge for persistence. "
        "Use `extend_thinking_graph` sparingly when you discover genuinely separate sub-topics.\n\n"
        "For code-level work (reading files, analyzing code, git history), use `dispatch_coding_agent` "
        "(async — returns immediately). For multiple code tasks, create separate vertices instead."
        + _DECOMPOSE_HINT + _LANGUAGE_HINT
    ),
    VertexType.EXECUTOR: (
        "You are the Executor. Complete the assigned task using the provided context and tools. "
        "Be thorough, precise, and produce actionable output. "
        "Use tools (kb_search, etc.) to fetch detailed information you need. "
        "Use `ask_user` only when critical information is truly missing. "
        "Use `store_knowledge` to persist important findings.\n\n"
        "Use `extend_thinking_graph` sparingly when your task reveals genuinely separate work.\n\n"
        "For code-level work (reading/writing files, running tests), use `dispatch_coding_agent` "
        "(async — returns immediately). For multiple code tasks, create separate vertices instead.\n\n"
        "SCHEDULED TASKS: `create_scheduled_task` is ONLY for time-bound deadlines (due dates, "
        "specific calendar triggers). NEVER use it to defer work you can do NOW. Regular work "
        "goes through the normal task queue — do the work directly using available tools."
        + _DECOMPOSE_HINT + _LANGUAGE_HINT
    ),
    VertexType.TASK: (
        "You are the Executor. Complete the assigned task using the provided context and tools. "
        "Be thorough, precise, and produce actionable output. "
        "Use tools (kb_search, etc.) to fetch detailed information you need. "
        "Use `ask_user` only when critical information is truly missing. "
        "Use `store_knowledge` to persist important findings.\n\n"
        "Use `extend_thinking_graph` sparingly when your task reveals genuinely separate work.\n\n"
        "For code-level work (reading/writing files, running tests), use `dispatch_coding_agent` "
        "(async — returns immediately). For multiple code tasks, create separate vertices instead.\n\n"
        "SCHEDULED TASKS: `create_scheduled_task` is ONLY for time-bound deadlines (due dates, "
        "specific calendar triggers). NEVER use it to defer work you can do NOW. Regular work "
        "goes through the normal task queue — do the work directly using available tools."
        + _DECOMPOSE_HINT + _LANGUAGE_HINT
    ),
    VertexType.VALIDATOR: (
        "You are the Validator. Verify upstream results for correctness and completeness. "
        "Use tools to check claims and artifacts. "
        "Conclude with: PASS (all good) or FAIL (with specific issues).\n\n"
        "For code verification, use `dispatch_coding_agent`."
        + _DECOMPOSE_HINT + _LANGUAGE_HINT
    ),
    VertexType.REVIEWER: (
        "You are the Reviewer. Review upstream work for quality and potential improvements. "
        "Use tools to verify claims. Provide constructive feedback. "
        "Conclude with: APPROVED, NEEDS_CHANGES, or REJECTED.\n\n"
        "For code review, use `dispatch_coding_agent`."
        + _DECOMPOSE_HINT + _LANGUAGE_HINT
    ),
    VertexType.SYNTHESIS: (
        "You are the Synthesizer. Combine upstream results into a coherent, unified response. "
        "Preserve key details, resolve contradictions, and note any failures. "
        "Always respond in Czech.\n\n"
        "If the combined result is too large or complex for a single pass, use `extend_thinking_graph` "
        "to create follow-up vertices that each write a section of the final document. "
        "Then create a final synthesis vertex to combine those sections."
        + _LANGUAGE_HINT
    ),
    VertexType.GATE: (
        "You are the Gate. Evaluate upstream results and decide whether to proceed. "
        'Respond with JSON: {"proceed": true/false, "reason": "..."}'
    ),
    VertexType.SETUP: (
        "You are the Setup Agent. Handle infrastructure, scaffolding, and environment provisioning. "
        "Search KB for any accumulated requirements and specifications first. "
        "Confirm key decisions with the user via `ask_user` before creating infrastructure. "
        "Use `dispatch_coding_agent` for code generation (not inline code). "
        "Use environment tools for provisioning."
        + _DECOMPOSE_HINT
    ),
}

# Safety limit for vertex iterations — NOT a target or expected count.
# Actual termination is driven by loop/stagnation detection.
# This only prevents infinite loops if detection fails.
_MAX_VERTEX_TOOL_ITERATIONS = 100
# Consecutive iterations with no new meaningful content → force finish
_STAGNATION_THRESHOLD = 5
# Max extend_thinking_graph calls per single vertex (prevent runaway growth)
_MAX_EXTEND_GRAPH_PER_VERTEX = 1
# Timeout for a single tool execution within vertex loop
_VERTEX_TOOL_TIMEOUT_S = 90  # KB graph traversals can take 30s+
# NO overall vertex timeout — vertices can take hours when GPU is busy
# or waiting for cloud escalation. Never time out a vertex.

# Dummy tool for force-finish mode — forces blocking LLM call (provider uses
# streaming when tools=None, which can hang on large contexts).
_NOOP_FINISH_TOOL: dict = {
    "type": "function",
    "function": {
        "name": "_finish",
        "description": "Signal that you are done. Call this ONLY if you cannot produce a text answer.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
}


# ---------------------------------------------------------------------------
# Agentic tool loop — core execution engine for each vertex
# ---------------------------------------------------------------------------


async def _agentic_vertex(
    vertex: GraphVertex,
    context: str,
    state: dict,
    *,
    graph: AgentGraph | None = None,
) -> tuple[str, str]:
    """Execute a vertex with an agentic tool loop.

    1. Load default tools for the vertex type
    2. Call LLM with tools
    3. If LLM returns tool calls → execute them → append results → repeat
    4. If LLM returns text (no tool calls) → that's the final result
    5. Handle `request_tools` meta-tool by adding requested categories
    6. Handle `extend_thinking_graph` by adding new vertices to the graph

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

    # --- Build system prompt with task scope ---
    system_prompt = _SYSTEM_PROMPTS.get(
        vertex.vertex_type,
        _SYSTEM_PROMPTS[VertexType.EXECUTOR],
    )

    # Inject task scope — agent must know client/project context
    task_data = state.get("task", {})
    client_name = task_data.get("client_name", "")
    project_name = task_data.get("project_name", "")
    if client_name or project_name:
        system_prompt += (
            f"\n\n## Task Scope\n"
            f"Client: {client_name} (id={task_data.get('client_id', '')})\n"
            f"Project: {project_name} (id={task_data.get('project_id', '')})\n"
            f"You are working within this specific client and project. "
            f"Do NOT ask about client or project selection — it's already determined. "
            f"All tool calls (kb_search, store_knowledge, etc.) are automatically scoped to this client/project."
        )

    # --- Load default tools ---
    tools = get_default_tools(vertex.vertex_type)

    # --- Build initial messages ---
    # Context from edges = summaries only (keep vertex context minimal)
    user_content = (
        f"## {vertex.title}\n\n{vertex.description}\n\n"
        f"## Context from upstream vertices\n{context}"
    )

    messages: list[dict] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_content},
    ]

    # --- Extract IDs for tool execution (task_data already loaded above) ---
    client_id = task_data.get("client_id", "")
    project_id = task_data.get("project_id")
    task_id = task_data.get("id", "")
    group_id = task_data.get("group_id")

    # --- Agentic loop ---
    tool_call_history: list[tuple[str, str]] = []
    extend_thinking_graph_calls = 0  # Track how many times this vertex spawned children
    iteration = 0
    result = ""
    stagnation_counter = 0  # Consecutive iterations without new meaningful results
    last_unique_tool_count = 0  # Track unique tool calls to detect stagnation

    while iteration < _MAX_VERTEX_TOOL_ITERATIONS:
        iteration += 1

        # --- Cancellation check: bail out if graph was cancelled externally ---
        graph_data = state.get("task_graph")
        if graph_data:
            live_graph = AgentGraph(**graph_data) if isinstance(graph_data, dict) else graph_data
            if live_graph.status == GraphStatus.CANCELLED:
                logger.info("Vertex %s: graph cancelled, aborting agentic loop", vertex.id)
                vertex.status = VertexStatus.CANCELLED
                return ("Cancelled by user.", "Cancelled")

        # --- Stagnation detection: force finish if no progress ---
        if stagnation_counter >= _STAGNATION_THRESHOLD and tools:
            logger.info(
                "Vertex %s: stagnation detected (%d iterations without progress) at iteration %d — forcing finish",
                vertex.id, stagnation_counter, iteration,
            )
            tools = []
            messages.append({
                "role": "user",
                "content": (
                    "Tvé poslední tool calls nepřinesly nové informace. "
                    "Shrň vše co jsi zjistil a ODPOVĚZ. Nevolej další tools."
                ),
            })

        # Pass tools=None when we never had tools; but when force-finish sets tools=[],
        # pass a dummy tool to force blocking mode (streaming can hang on large contexts).
        # Provider uses blocking mode when tools is truthy, streaming when tools is None.
        effective_tools = tools if tools else None
        if tools is not None and not tools:
            # Force-finish: tools=[] — use a no-op tool to force blocking mode
            effective_tools = [_NOOP_FINISH_TOOL]

        response = await llm_with_cloud_fallback(
            state=state,
            messages=messages,
            task_type="graph_vertex",
            max_tokens=settings.default_output_tokens,
            tools=effective_tools,
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

        # Filter out tool calls for tools not in current list
        # (Ollama models may generate calls for removed tools from conversation history)
        # Note: tools=[] (explicitly empty) means ALL tools removed — filter everything
        # Filter phantom tool calls: model may generate calls for removed tools
        # from conversation history. Also handle force-finish (_finish noop tool).
        if tools is not None:
            available_names = {t.get("function", {}).get("name") for t in tools}
            # Always allow meta-tools (unless tools is explicitly empty = force-finish mode)
            if tools:
                available_names.update({"request_tools", "extend_thinking_graph", "decompose_task"})
            else:
                # Force-finish mode: only allow _finish noop
                available_names = {"_finish"}

            filtered = [tc for tc in tool_calls if tc.function.name in available_names]
            if len(filtered) < len(tool_calls):
                removed = [tc.function.name for tc in tool_calls if tc.function.name not in available_names]
                logger.warning("Filtered out %d phantom tool calls: %s", len(removed), removed)
            tool_calls = filtered

            # If only _finish or no tool calls remain → done
            finish_only = all(tc.function.name == "_finish" for tc in tool_calls) if tool_calls else True
            if not tool_calls or finish_only:
                # Use whatever text we have
                candidate = remaining_text or message.content or ""
                if candidate:
                    result = candidate
                elif not result:
                    result = _extract_accumulated_result(messages)
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

            # Handle the decompose_task meta-tool — creates children, interrupts loop
            if tool_name == "decompose_task":
                _handle_decompose_task(arguments, vertex, graph)
                # Raise DecomposeInterrupt to stop the agentic loop
                # Children are created, vertex will transition to WAITING_CHILDREN
                raise DecomposeInterrupt()

            # Handle the extend_thinking_graph meta-tool
            if tool_name == "extend_thinking_graph":
                # Hard limit: max N extend_thinking_graph calls per vertex
                if extend_thinking_graph_calls >= _MAX_EXTEND_GRAPH_PER_VERTEX:
                    messages.append({
                        "role": "tool",
                        "tool_call_id": tc.id,
                        "name": tool_name,
                        "content": (
                            f"ERROR: Maximum extend_thinking_graph calls ({_MAX_EXTEND_GRAPH_PER_VERTEX}) "
                            "reached for this vertex. Focus on YOUR task and produce a result. "
                            "Do NOT try to extend the thinking graph again."
                        ),
                    })
                    # Remove the tool to prevent further attempts
                    if tools:
                        tools = [t for t in tools if t.get("function", {}).get("name") != tool_name]
                        logger.warning("Removed extend_thinking_graph after %d calls (hard limit)", extend_thinking_graph_calls)
                    continue

                # Loop detection for extend_thinking_graph — prevent repeated calls with same args
                loop_reason, loop_count = detect_tool_loop(
                    tool_call_history, tool_name, arguments,
                )
                if loop_reason:
                    messages.append({
                        "role": "tool",
                        "tool_call_id": tc.id,
                        "name": tool_name,
                        "content": (
                            f"ERROR: {loop_reason} "
                            "The vertices were already created. Continue with your current analysis — "
                            "do NOT call extend_thinking_graph again with the same vertices."
                        ),
                    })
                    if loop_count >= 2 and tools:
                        tools = [t for t in tools if t.get("function", {}).get("name") != tool_name]
                        logger.warning("Removed extend_thinking_graph from tools after %d repeats", loop_count)
                    continue
                extend_thinking_graph_calls += 1
                tool_result = _handle_extend_thinking_graph(arguments, vertex, graph)
                messages.append({
                    "role": "tool",
                    "tool_call_id": tc.id,
                    "name": tool_name,
                    "content": tool_result,
                })
                continue

            # Loop detection — prevent repeated calls with identical arguments
            loop_reason, loop_count = detect_tool_loop(
                tool_call_history, tool_name, arguments,
            )
            if loop_reason:
                messages.append({
                    "role": "tool",
                    "tool_call_id": tc.id,
                    "name": tool_name,
                    "content": (
                        f"ERROR: {loop_reason} "
                        "You already called this tool with the same arguments. "
                        "Use a DIFFERENT tool or provide your final answer."
                    ),
                })
                # After 2nd repeat, remove the tool entirely
                if loop_count >= 2 and tools:
                    tools = [t for t in tools if t.get("function", {}).get("name") != tool_name]
                    logger.warning("Removed tool %s from available tools after %d repeats", tool_name, loop_count)
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
                        group_id=group_id,
                        task_id=task_id,
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

        # --- Stagnation tracking: count unique tool calls ---
        current_unique = len(set(tool_call_history))
        if current_unique > last_unique_tool_count:
            # New unique tool call → progress, reset stagnation
            stagnation_counter = 0
            last_unique_tool_count = current_unique
        else:
            # No new unique tool calls → stagnation
            stagnation_counter += 1
    else:
        # Safety max iterations reached — use whatever we have
        logger.warning(
            "Vertex %s hit safety max iterations (%d)",
            vertex.id, _MAX_VERTEX_TOOL_ITERATIONS,
        )
        if not result:
            result = remaining_text or "(safety limit reached)"

    summary = result
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
            return output.result, output.result
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


def _extract_accumulated_result(messages: list[dict]) -> str:
    """Extract useful text from message history when vertex has no explicit result.

    Collects tool results and any assistant text to build a summary.
    Used as fallback when force-finish strips tools and model produces no text.
    """
    parts = []
    for msg in messages:
        if msg.get("role") == "tool":
            content = msg.get("content", "")
            if content and not content.startswith("ERROR:") and len(content) > 20:
                parts.append(content)
        elif msg.get("role") == "assistant":
            content = msg.get("content", "")
            if content and len(content) > 10:
                parts.append(content)
    if parts:
        return "\n---\n".join(parts[-5:])  # Last 5 meaningful pieces
    return "(vertex completed without producing explicit result)"


def _handle_extend_thinking_graph(
    arguments: dict,
    vertex: GraphVertex,
    graph: AgentGraph | None,
) -> str:
    """Handle the `extend_thinking_graph` meta-tool call.

    Dynamically adds new vertices to the thinking graph during execution.
    New vertices depend on the CURRENT vertex — they will be picked up
    by select_next after the current vertex completes.

    Returns a confirmation message for the LLM.
    """
    from app.agent.graph import add_vertex, add_edge
    from app.agent.models import EdgeType, VertexStatus, VertexType
    from app.agent.decomposer import MAX_TOTAL_VERTICES

    if graph is None:
        return "ERROR: extend_thinking_graph is not available in this context (no graph reference)."

    vertices_data = arguments.get("vertices", [])
    reason = arguments.get("reason", "")
    connect_to_synthesis = arguments.get("connect_to_synthesis", False)
    create_final_synthesis = arguments.get("create_final_synthesis", False)

    if not vertices_data:
        return "ERROR: No vertices provided."

    # --- GLOBAL EXTEND CAP ---
    _MAX_EXTEND_TOTAL = 30  # Max total vertices added via extend across entire graph
    if graph.extend_count >= _MAX_EXTEND_TOTAL:
        return (
            f"ERROR: Graph extend limit reached ({graph.extend_count}/{_MAX_EXTEND_TOTAL} vertices added). "
            "Focus on completing your task with the information you have. "
            "Use kb_search to find what you need instead of creating new vertices."
        )

    # Enforce per-call limits
    if len(vertices_data) > 8:
        vertices_data = vertices_data[:8]

    if len(graph.vertices) + len(vertices_data) > MAX_TOTAL_VERTICES:
        remaining = MAX_TOTAL_VERTICES - len(graph.vertices)
        if remaining <= 0:
            return f"ERROR: Graph already has {len(graph.vertices)} vertices (max {MAX_TOTAL_VERTICES}). Cannot add more."
        vertices_data = vertices_data[:remaining]

    # --- DEDUPLICATION: check existing vertices for same/similar titles ---
    # Build index of existing titles (normalized)
    existing_titles: dict[str, str] = {}  # normalized_title → vertex_id
    for vid, ev in graph.vertices.items():
        normalized = ev.title.lower().strip()
        if normalized:
            existing_titles[normalized] = vid

    created: list[str] = []
    reused: list[str] = []  # Existing vertices we connected to instead of creating
    type_map = {
        "investigator": VertexType.INVESTIGATOR,
        "executor": VertexType.EXECUTOR,
        "task": VertexType.TASK,
        "validator": VertexType.VALIDATOR,
        "synthesis": VertexType.SYNTHESIS,
    }

    for vd in vertices_data:
        new_title = (vd.get("title") or "Untitled").strip()
        normalized = new_title.lower()
        vtype_str = vd.get("type", "task")
        vtype = type_map.get(vtype_str, VertexType.TASK)

        # Check for duplicate: exact title match or very similar
        existing_vid = existing_titles.get(normalized)
        if not existing_vid:
            # Fuzzy: check if any existing title contains this or vice versa
            for et, evid in existing_titles.items():
                if len(normalized) > 10 and len(et) > 10:
                    if normalized in et or et in normalized:
                        existing_vid = evid
                        break

        if existing_vid:
            # REUSE: connect current vertex to existing one via edge (back-reference)
            existing_v = graph.vertices[existing_vid]
            # Only reuse if it's completed (has useful results to share)
            if existing_v.status == VertexStatus.COMPLETED:
                # Add edge so current vertex's downstream gets this vertex's context
                # Check edge doesn't already exist
                edge_exists = any(
                    e.source_id == existing_vid and e.target_id == vertex.id
                    for e in graph.edges
                )
                if not edge_exists:
                    reused.append(existing_vid)
                logger.info(
                    "DEDUP: Reusing existing vertex '%s' (%s) instead of creating duplicate",
                    existing_v.title[:40], existing_vid[:16],
                )
                continue
            # If existing vertex is still pending/running, skip creating duplicate
            if existing_v.status in (VertexStatus.PENDING, VertexStatus.READY, VertexStatus.RUNNING):
                logger.info(
                    "DEDUP: Skipping duplicate '%s' — vertex %s already %s",
                    new_title[:40], existing_vid[:16], existing_v.status.value,
                )
                continue

        # No duplicate found — create new vertex
        new_v = add_vertex(
            graph=graph,
            title=new_title,
            description=vd.get("description", ""),
            vertex_type=vtype,
            parent_id=vertex.parent_id or vertex.id,
            input_request=vd.get("description", ""),
            client_id=vertex.client_id,
            project_id=vertex.project_id,
        )

        # New vertex depends on current vertex (executes after it completes)
        add_edge(graph, vertex.id, new_v.id, EdgeType.DEPENDENCY)
        created.append(new_v.id)
        # Update index for next iteration dedup
        existing_titles[normalized] = new_v.id

    # Update global extend counter
    graph.extend_count += len(created)

    # --- CONVERGENCE GUARANTEE ---
    # Always connect new vertices to the root synthesis so the graph converges.
    # New vertices added via extend_thinking_graph must feed into the final synthesis,
    # otherwise their results become orphaned terminal vertices.
    root_synth_id = graph.synthesis_vertex_id
    if root_synth_id and root_synth_id in graph.vertices:
        root_synth = graph.vertices[root_synth_id]
        # Only connect if root synthesis hasn't started yet
        if root_synth.status in (VertexStatus.PENDING, VertexStatus.READY):
            non_synthesis_created = [
                cid for cid in created
                if graph.vertices[cid].vertex_type != VertexType.SYNTHESIS
            ]
            for cid in non_synthesis_created:
                add_edge(graph, cid, root_synth_id, EdgeType.DEPENDENCY)
            if non_synthesis_created:
                logger.info(
                    "CONVERGENCE: Connected %d new vertices to root synthesis %s",
                    len(non_synthesis_created), root_synth_id,
                )
    elif not root_synth_id and len(graph.vertices) > 3:
        # No root synthesis exists — create one (safety net for old graphs)
        synth_v = add_vertex(
            graph=graph,
            title="Syntéza výsledků",
            description="Combine all results into a coherent answer to the original request.",
            vertex_type=VertexType.SYNTHESIS,
            parent_id=vertex.parent_id or vertex.id,
            input_request="Synthesize all upstream results",
            client_id=vertex.client_id,
            project_id=vertex.project_id,
        )
        graph.synthesis_vertex_id = synth_v.id
        for cid in created:
            add_edge(graph, cid, synth_v.id, EdgeType.DEPENDENCY)
        created.append(synth_v.id)
        logger.info(
            "CONVERGENCE: Created root synthesis %s for %d new vertices",
            synth_v.id, len(created) - 1,
        )

    # Legacy: still support connect_to_synthesis/create_final_synthesis for
    # sub-synthesis within branches (e.g. 3 sub-investigators → local synthesis → root synthesis)
    if create_final_synthesis and len(created) > 1:
        synth_v = add_vertex(
            graph=graph,
            title="Synthesize extended results",
            description="Combine results from extended investigation into a summary.",
            vertex_type=VertexType.SYNTHESIS,
            parent_id=vertex.parent_id or vertex.id,
            input_request="Synthesize all upstream results",
            client_id=vertex.client_id,
            project_id=vertex.project_id,
        )
        non_synthesis = [cid for cid in created if graph.vertices[cid].vertex_type != VertexType.SYNTHESIS]
        for cid in non_synthesis:
            add_edge(graph, cid, synth_v.id, EdgeType.DEPENDENCY)
        # Connect sub-synthesis to root synthesis
        if root_synth_id and root_synth_id in graph.vertices:
            root_synth = graph.vertices[root_synth_id]
            if root_synth.status in (VertexStatus.PENDING, VertexStatus.READY):
                add_edge(graph, synth_v.id, root_synth_id, EdgeType.DEPENDENCY)
        created.append(synth_v.id)

    dedup_count = len(vertices_data) - len(created)
    logger.info(
        "Vertex %s extended thinking graph: +%d new, %d deduped, %d reused, total_extends=%d/%d, reason=%s",
        vertex.id, len(created), dedup_count, len(reused),
        graph.extend_count, _MAX_EXTEND_TOTAL, reason,
    )

    if not created and not reused:
        return (
            "All requested vertices already exist in the thinking graph (deduplicated). "
            "Use the existing results — search with kb_search or check your incoming context."
        )

    parts = []
    if created:
        new_titles = [graph.vertices[cid].title for cid in created]
        parts.append(f"Created {len(created)} new vertices: {', '.join(new_titles)}")
    if reused:
        reused_titles = [graph.vertices[rid].title[:30] for rid in reused]
        parts.append(f"Reused {len(reused)} existing: {', '.join(reused_titles)}")
    if dedup_count > 0:
        parts.append(f"Skipped {dedup_count} duplicates")

    return ". ".join(parts) + ". Continue with your current analysis."


def _handle_decompose_task(
    arguments: dict,
    vertex: GraphVertex,
    graph: AgentGraph | None,
) -> None:
    """Handle the `decompose_task` meta-tool call.

    Creates child TASK vertices under the current vertex. The vertex will
    transition to WAITING_CHILDREN and resume when all children complete.

    Raises ValueError if limits are exceeded (caught by agentic loop as failure).
    """
    if graph is None:
        raise ValueError("decompose_task is not available in this context (no graph reference)")

    subtasks = arguments.get("subtasks", [])
    if not subtasks:
        raise ValueError("No subtasks provided to decompose_task")

    # Validate subtask format
    children_specs = []
    for st in subtasks[:10]:  # Max 10 subtasks
        title = st.get("title", "").strip()
        description = st.get("description", "").strip()
        if not title:
            continue
        children_specs.append({"title": title, "description": description or title})

    if not children_specs:
        raise ValueError("No valid subtasks provided (all missing titles)")

    # Create children via decomposer
    created = create_child_vertices(graph, vertex, children_specs)

    logger.info(
        "DECOMPOSE_TASK | vertex=%s | title='%s' | created=%d children",
        vertex.id, vertex.title, len(created),
    )


def _build_context_with_children_results(
    vertex: GraphVertex,
    base_context: str,
) -> str:
    """Build context for a resumed parent vertex with children results.

    When a vertex is resumed from WAITING_CHILDREN, its incoming_context
    contains EdgePayloads from children. Combine with original task context.
    """
    parts = [f"## Original Task\n{vertex.input_request}"]

    if vertex.incoming_context:
        parts.append("\n## Results from sub-tasks")
        for payload in vertex.incoming_context:
            parts.append(
                f"\n### {payload.source_vertex_title}\n{payload.summary}"
            )

    parts.append(
        "\n## Instructions\n"
        "Review the sub-task results above. Combine them into a coherent, "
        "complete answer to the original task. If any sub-task failed, "
        "note the failure and work around it if possible."
    )

    return "\n".join(parts)


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
    graph: AgentGraph | None = None,
) -> tuple[str, str]:
    """Unified dispatch — all vertex types go through the agentic loop."""
    return await _agentic_vertex(vertex, context, state, graph=graph)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _build_context(vertex: GraphVertex) -> str:
    """Build context string from vertex's incoming edge payloads.

    IMPORTANT: Only summaries are passed between vertices via edges.
    Full context stays local — vertex must use tools (kb_search, etc.)
    to fetch detailed data if needed. This keeps token usage minimal
    and prevents context explosion in deep graphs.
    """
    if not vertex.incoming_context:
        return vertex.input_request

    parts = [f"## Task\n{vertex.input_request}"]
    for payload in vertex.incoming_context:
        # Edge carries ONLY summary — never full context
        parts.append(
            f"\n### From: {payload.source_vertex_title}\n"
            f"{payload.summary}"
        )
    return "\n".join(parts)

