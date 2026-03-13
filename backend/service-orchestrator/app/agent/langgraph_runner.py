"""LangGraph-based runner for Graph Agent.

Uses LangGraph (proven framework) for execution, with AgentGraph as the
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

AgentGraph is carried in LangGraph state — LangGraph handles checkpointing,
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
from langgraph.errors import GraphInterrupt
from langgraph.types import interrupt

from app.config import settings
from app.graph.nodes._helpers import (
    detect_tool_loop,
    llm_with_cloud_fallback,
    parse_json_response,
)
from app.agent.decomposer import decompose_root, decompose_vertex
from app.agent.graph import (
    block_vertex,
    complete_vertex,
    fail_vertex,
    find_blocked_vertices,
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
    """Decompose the user request into a AgentGraph (vertices + edges).

    ALWAYS goes through LLM decomposition — even simple questions like
    "kolik je hodin?" because complexity can't be judged from text length.
    ("jaký je stav projektu?" is short but needs deep analysis.)

    The decomposer LLM decides: simple → 1 vertex, complex → multiple vertices.
    """
    # Pre-built thinking map — skip decomposition, graph already has vertices
    if state.get("task_graph"):
        graph = AgentGraph(**state["task_graph"])
        agent_store.cache_subgraph(graph)
        agent_store.mark_dirty(graph.task_id)
        logger.info(
            "Skipping decomposition — pre-built thinking map with %d vertices",
            len(graph.vertices),
        )
        await report_graph_status(graph, f"Pre-built map with {len(graph.vertices) - 1} vertices")
        return {"current_vertex_id": None, "graph_error": None}

    if "task" not in state:
        return {"graph_error": "Missing task context — cannot decompose (stale checkpoint?)"}
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

    # Cache in RAM + mark dirty for async DB flush
    agent_store.cache_subgraph(graph)
    agent_store.mark_dirty(graph.task_id)
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

    graph = AgentGraph(**graph_data)

    # Stop scheduling if graph is cancelled or failed
    if graph.status == GraphStatus.CANCELLED:
        logger.info("Graph %s cancelled — stopping vertex scheduling", graph.id)
        return {"task_graph": graph.model_dump(), "current_vertex_id": None}
    if graph.status == GraphStatus.FAILED:
        logger.info("Graph %s has failures — stopping vertex scheduling", graph.id)
        return {"task_graph": graph.model_dump(), "current_vertex_id": None}

    ready = get_ready_vertices(graph)

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
    """Execute a single vertex (shared logic for serial and parallel paths)."""
    vertex = start_vertex(graph, vertex_id)
    if not vertex:
        return graph

    # Immediately flush RUNNING status to DB so UI sees "Probíhá" right away
    agent_store.cache_subgraph(graph)
    await agent_store.save(graph)

    await report_vertex_started(graph, vertex_id)

    try:
        # PLANNER/DECOMPOSE → recursive decomposition (creates sub-graph)
        if vertex.vertex_type in (VertexType.PLANNER, VertexType.DECOMPOSE):
            graph = await _handle_decompose_vertex(graph, vertex, state)
        else:
            # All other types → agentic tool loop (NO timeout — GPU can be busy for hours)
            context = _build_context(vertex)
            result, summary = await _dispatch_vertex_handler(vertex, context, state, graph=graph)

            # Complete vertex — fills outgoing edge payloads
            complete_vertex(
                graph, vertex_id,
                result=result,
                result_summary=summary,
                local_context=result,
            )

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
        and completed_vertex.vertex_type not in (VertexType.PLANNER, VertexType.DECOMPOSE)
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
    """Compose the final result from completed vertices using LLM synthesis."""
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

    # Final save — synchronous (graph is complete, flush immediately)
    await agent_store.save(graph)
    agent_store.remove_cached_subgraph(graph.task_id)
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

    # Check for pre-existing thinking map (created by dispatch_map in chat,
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

        # Mark root vertex as COMPLETED if not already (decomposition already done)
        root = existing_graph.vertices.get(existing_graph.root_vertex_id)
        if root and root.status != VertexStatus.COMPLETED:
            complete_vertex(
                existing_graph, root.id,
                result="Pre-built thinking map (decomposed by chat)",
                result_summary="Pre-built thinking map",
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
_SYSTEM_PROMPTS: dict[VertexType, str] = {
    VertexType.PLANNER: (
        "You are the Planner. Analyze the task and create a structured plan. "
        "Break it into clear, actionable steps. Use tools to gather information as needed."
    ),
    VertexType.DECOMPOSE: (
        "You are the Planner. Analyze the task and create a structured plan. "
        "Break it into clear, actionable steps. Use tools to gather information as needed."
    ),
    VertexType.INVESTIGATOR: (
        "You are the Investigator. Research the topic thoroughly using the provided tools. "
        "Compile findings, identify gaps, and cite sources. "
        "Your output summary will be passed to downstream vertices via edges. "
        "Keep your output concise — downstream vertices only see a brief summary, "
        "not your full context. Store important findings via store_knowledge for persistence.\n\n"
        "DYNAMIC MAP GROWTH: When you discover sub-topics or aspects that need separate investigation, "
        "use `extend_thinking_map` to create new vertices. This grows the thinking map organically. "
        "Each new vertex will execute after you complete and receive your findings as context.\n\n"
        "CRITICAL: You NEVER investigate code, files, or git history directly. "
        "You have basic git metadata tools (branch list, recent commits) for orientation ONLY. "
        "For ANY code analysis, file reading, git history investigation, or code changes "
        "you MUST use `dispatch_coding_agent`. The orchestrator is the brain — it decides "
        "what needs to happen. The coding agent is the hand — it executes code-level work.\n\n"
        "IMPORTANT: dispatch_coding_agent is ASYNC — it returns immediately while the job runs. "
        "If you need multiple code analyses, create separate vertices via extend_thinking_map instead."
    ),
    VertexType.EXECUTOR: (
        "You are the Executor. Complete the assigned task using the provided context and tools. "
        "Be thorough, precise, and produce actionable output. "
        "You receive only brief summaries from upstream vertices — use tools (kb_search, etc.) "
        "to fetch any detailed information you need. "
        "Use `ask_user` ONLY when absolutely critical information is missing. "
        "Use `store_knowledge` to persist important findings and decisions for future reference.\n\n"
        "DYNAMIC MAP GROWTH: Use `extend_thinking_map` to create new vertices when your task "
        "reveals additional work that should be handled separately. New vertices execute after you.\n\n"
        "CRITICAL: You NEVER investigate code or files directly. "
        "For ANY code analysis, file reading, code changes, or git operations "
        "you MUST use `dispatch_coding_agent`. You are the brain — you decide and coordinate. "
        "The coding agent is the hand — it reads code, writes code, runs tests, manages git.\n\n"
        "IMPORTANT: dispatch_coding_agent is ASYNC — it returns immediately while the job runs. "
        "If you need multiple code analyses, create separate vertices via extend_thinking_map instead."
    ),
    VertexType.TASK: (
        "You are the Executor. Complete the assigned task using the provided context and tools. "
        "Be thorough, precise, and produce actionable output. "
        "You receive only brief summaries from upstream vertices — use tools (kb_search, etc.) "
        "to fetch any detailed information you need. "
        "Use `ask_user` ONLY when absolutely critical information is missing. "
        "Use `store_knowledge` to persist important findings and decisions for future reference.\n\n"
        "DYNAMIC MAP GROWTH: Use `extend_thinking_map` to create new vertices when your task "
        "reveals additional work that should be handled separately. New vertices execute after you.\n\n"
        "CRITICAL: You NEVER investigate code or files directly. "
        "For ANY code analysis, file reading, code changes, or git operations "
        "you MUST use `dispatch_coding_agent`. You are the brain — you decide and coordinate. "
        "The coding agent is the hand — it reads code, writes code, runs tests, manages git.\n\n"
        "IMPORTANT: dispatch_coding_agent is ASYNC — it returns immediately while the job runs. "
        "If you need multiple code analyses, create separate vertices via extend_thinking_map instead."
    ),
    VertexType.VALIDATOR: (
        "You are the Validator. Verify the upstream results for correctness and completeness. "
        "Use tools to check claims and artifacts. "
        "Conclude with: PASS (all good) or FAIL (with specific issues).\n\n"
        "For code verification, use `dispatch_coding_agent` — never investigate code directly."
    ),
    VertexType.REVIEWER: (
        "You are the Reviewer. Review the upstream work for quality and potential improvements. "
        "Use tools to verify claims. Provide constructive feedback. "
        "Conclude with: APPROVED, NEEDS_CHANGES, or REJECTED.\n\n"
        "For code review, use `dispatch_coding_agent` — never investigate code directly."
    ),
    VertexType.SYNTHESIS: (
        "You are the Synthesizer. Combine upstream results into a coherent, unified response. "
        "Preserve key details, resolve contradictions, and note any failures. "
        "Use the user's language.\n\n"
        "If the combined result is too large or complex for a single pass, use `extend_thinking_map` "
        "to create follow-up vertices that each write a section of the final document. "
        "Then create a final synthesis vertex to combine those sections."
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
    ),
}

# Max agentic loop iterations per vertex (tool call rounds)
_MAX_VERTEX_TOOL_ITERATIONS = 15
# Max extend_thinking_map calls per single vertex (prevent runaway growth)
_MAX_EXTEND_MAP_PER_VERTEX = 1
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
    6. Handle `extend_thinking_map` by adding new vertices to the graph

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
    extend_thinking_map_calls = 0  # Track how many times this vertex spawned children
    iteration = 0
    result = ""

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

        # --- Force finish: at 50% iterations, strip all tools to get a text answer ---
        force_finish_at = int(_MAX_VERTEX_TOOL_ITERATIONS * 0.50)
        if iteration == force_finish_at and tools:
            logger.info(
                "Vertex %s: forcing finish at iteration %d/%d — removing all tools",
                vertex.id, iteration, _MAX_VERTEX_TOOL_ITERATIONS,
            )
            tools = []
            messages.append({
                "role": "user",
                "content": (
                    "You are running out of iterations. Produce your FINAL ANSWER now. "
                    "Summarize everything you have found so far. Do NOT call any more tools."
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
                available_names.update({"request_tools", "extend_thinking_map"})
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

            # Handle the extend_thinking_map meta-tool
            if tool_name == "extend_thinking_map":
                # Hard limit: max N extend_thinking_map calls per vertex
                if extend_thinking_map_calls >= _MAX_EXTEND_MAP_PER_VERTEX:
                    messages.append({
                        "role": "tool",
                        "tool_call_id": tc.id,
                        "name": tool_name,
                        "content": (
                            f"ERROR: Maximum extend_thinking_map calls ({_MAX_EXTEND_MAP_PER_VERTEX}) "
                            "reached for this vertex. Focus on YOUR task and produce a result. "
                            "Do NOT try to extend the thinking map again."
                        ),
                    })
                    # Remove the tool to prevent further attempts
                    if tools:
                        tools = [t for t in tools if t.get("function", {}).get("name") != tool_name]
                        logger.warning("Removed extend_thinking_map after %d calls (hard limit)", extend_thinking_map_calls)
                    continue

                # Loop detection for extend_thinking_map — prevent repeated calls with same args
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
                            "do NOT call extend_thinking_map again with the same vertices."
                        ),
                    })
                    if loop_count >= 2 and tools:
                        tools = [t for t in tools if t.get("function", {}).get("name") != tool_name]
                        logger.warning("Removed extend_thinking_map from tools after %d repeats", loop_count)
                    continue
                extend_thinking_map_calls += 1
                tool_result = _handle_extend_thinking_map(arguments, vertex, graph)
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
    else:
        # Max iterations reached — use whatever we have
        logger.warning(
            "Vertex %s hit max tool iterations (%d)",
            vertex.id, _MAX_VERTEX_TOOL_ITERATIONS,
        )
        if not result:
            result = remaining_text or "(max tool iterations reached)"

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
                parts.append(content[:500])
        elif msg.get("role") == "assistant":
            content = msg.get("content", "")
            if content and len(content) > 10:
                parts.append(content[:500])
    if parts:
        return "\n---\n".join(parts[-5:])  # Last 5 meaningful pieces
    return "(vertex completed without producing explicit result)"


def _handle_extend_thinking_map(
    arguments: dict,
    vertex: GraphVertex,
    graph: AgentGraph | None,
) -> str:
    """Handle the `extend_thinking_map` meta-tool call.

    Dynamically adds new vertices to the thinking map during execution.
    New vertices depend on the CURRENT vertex — they will be picked up
    by select_next after the current vertex completes.

    Returns a confirmation message for the LLM.
    """
    from app.agent.graph import add_vertex, add_edge
    from app.agent.models import EdgeType, VertexStatus, VertexType
    from app.agent.decomposer import MAX_TOTAL_VERTICES

    if graph is None:
        return "ERROR: extend_thinking_map is not available in this context (no graph reference)."

    vertices_data = arguments.get("vertices", [])
    reason = arguments.get("reason", "")
    connect_to_synthesis = arguments.get("connect_to_synthesis", False)
    create_final_synthesis = arguments.get("create_final_synthesis", False)

    if not vertices_data:
        return "ERROR: No vertices provided."

    # Enforce limits
    if len(vertices_data) > 8:
        vertices_data = vertices_data[:8]

    if len(graph.vertices) + len(vertices_data) > MAX_TOTAL_VERTICES:
        remaining = MAX_TOTAL_VERTICES - len(graph.vertices)
        if remaining <= 0:
            return f"ERROR: Graph already has {len(graph.vertices)} vertices (max {MAX_TOTAL_VERTICES}). Cannot add more."
        vertices_data = vertices_data[:remaining]

    # Create new vertices — all depend on current vertex
    created: list[str] = []
    type_map = {
        "investigator": VertexType.INVESTIGATOR,
        "executor": VertexType.EXECUTOR,
        "task": VertexType.TASK,
        "validator": VertexType.VALIDATOR,
        "synthesis": VertexType.SYNTHESIS,
    }

    for vd in vertices_data:
        vtype_str = vd.get("type", "task")
        vtype = type_map.get(vtype_str, VertexType.TASK)

        new_v = add_vertex(
            graph=graph,
            title=vd.get("title", "Untitled"),
            description=vd.get("description", ""),
            vertex_type=vtype,
            parent_id=vertex.parent_id or vertex.id,
            input_request=vd.get("description", ""),
            client_id=vertex.client_id,
            project_id=vertex.project_id,
        )

        # New vertex depends on current vertex (executes after it completes)
        add_edge(graph, vertex.id, new_v.id, EdgeType.DEPENDENCY)
        # Status stays PENDING — will become READY when current vertex completes
        # and complete_vertex fills the edge payload
        created.append(new_v.id)

    # Optionally connect to existing synthesis vertex
    if connect_to_synthesis:
        synthesis_vertices = [
            v for v in graph.vertices.values()
            if v.vertex_type == VertexType.SYNTHESIS
            and v.status in (VertexStatus.PENDING, VertexStatus.READY)
            and v.id not in created
        ]
        if synthesis_vertices:
            target_synthesis = synthesis_vertices[-1]  # Latest synthesis
            for cid in created:
                add_edge(graph, cid, target_synthesis.id, EdgeType.DEPENDENCY)
            logger.info(
                "Connected %d new vertices to existing synthesis %s",
                len(created), target_synthesis.id,
            )

    # Optionally create a new synthesis vertex
    if create_final_synthesis and len(created) > 1:
        synth_v = add_vertex(
            graph=graph,
            title="Synthesize extended results",
            description="Combine results from all extended investigation vertices into a coherent summary.",
            vertex_type=VertexType.SYNTHESIS,
            parent_id=vertex.parent_id or vertex.id,
            input_request="Synthesize all upstream results",
            client_id=vertex.client_id,
            project_id=vertex.project_id,
        )
        for cid in created:
            add_edge(graph, cid, synth_v.id, EdgeType.DEPENDENCY)
        created.append(synth_v.id)

    logger.info(
        "Vertex %s extended thinking map: +%d vertices, reason=%s",
        vertex.id, len(created), reason,
    )

    titles = [vd.get("title", "?") for vd in vertices_data]
    return (
        f"Added {len(vertices_data)} new vertices to the thinking map: {', '.join(titles)}. "
        f"They will execute after this vertex completes. "
        f"Continue with your current analysis — the new vertices will handle the sub-topics."
    )


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


async def _handle_decompose_vertex(
    graph: AgentGraph,
    vertex: GraphVertex,
    state: dict,
) -> AgentGraph:
    """Handle PLANNER/DECOMPOSE vertex — recursive decomposition.

    Instead of executing the vertex via LLM tool loop, calls decompose_vertex()
    to create sub-vertices + edges in the graph. The vertex itself is marked
    COMPLETED and its children will be picked up by subsequent select_next cycles.

    If decomposition fails or hits depth/count limits, the vertex is converted
    to EXECUTOR and falls through to the agentic tool loop on next dispatch.
    """
    from app.agent.decomposer import MAX_DECOMPOSE_DEPTH, MAX_TOTAL_VERTICES

    vertex_id = vertex.id

    # Check limits — if exceeded, convert to EXECUTOR and let agentic loop handle it
    if vertex.depth >= MAX_DECOMPOSE_DEPTH:
        logger.info(
            "Vertex %s at depth %d — max depth reached, converting to EXECUTOR",
            vertex_id, vertex.depth,
        )
        vertex.vertex_type = VertexType.EXECUTOR
        context = _build_context(vertex)
        result, summary = await _agentic_vertex(vertex, context, state, graph=graph)
        complete_vertex(graph, vertex_id, result=result, result_summary=summary, local_context=result)
        return graph

    if len(graph.vertices) >= MAX_TOTAL_VERTICES:
        logger.info(
            "Graph has %d vertices — max reached, converting %s to EXECUTOR",
            len(graph.vertices), vertex_id,
        )
        vertex.vertex_type = VertexType.EXECUTOR
        context = _build_context(vertex)
        result, summary = await _agentic_vertex(vertex, context, state, graph=graph)
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

