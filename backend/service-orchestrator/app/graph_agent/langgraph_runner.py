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
from typing import Any, TypedDict

from pymongo import MongoClient
from langgraph.checkpoint.mongodb import MongoDBSaver
from langgraph.graph import END, StateGraph

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
from app.models import CodingTask, DelegationMessage, OrchestrateRequest
from app.tools.executor import execute_tool
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
    ready = get_ready_vertices(graph)

    if not ready:
        return {
            "task_graph": graph.model_dump(),
            "current_vertex_id": None,
        }

    # Pick first ready vertex (topological order is maintained by readiness)
    vertex = ready[0]
    return {
        "task_graph": graph.model_dump(),
        "current_vertex_id": vertex.id,
    }


# ---------------------------------------------------------------------------
# Node: dispatch_vertex — routes to type-specific handler
# ---------------------------------------------------------------------------


async def node_dispatch_vertex(state: GraphAgentState) -> dict:
    """Execute the current vertex based on its type (responsibility).

    Each VertexType maps to a specific handler:
    - PLANNER/DECOMPOSE → _handle_planner
    - INVESTIGATOR      → _handle_investigator
    - EXECUTOR/TASK     → _handle_executor
    - VALIDATOR         → _handle_validator
    - REVIEWER          → _handle_reviewer
    - SYNTHESIS         → _handle_synthesis
    - GATE              → _handle_gate
    """
    graph = TaskGraph(**state["task_graph"])
    vertex_id = state["current_vertex_id"]
    vertex = start_vertex(graph, vertex_id)

    if not vertex:
        return {"task_graph": graph.model_dump()}

    await report_vertex_started(graph, vertex_id)

    try:
        # PLANNER/DECOMPOSE → recursive decomposition (creates sub-graph)
        if vertex.vertex_type in (VertexType.PLANNER, VertexType.DECOMPOSE):
            graph = await _handle_decompose_vertex(graph, vertex, state)
        else:
            # All other types → agentic tool loop
            context = _build_context(vertex)
            result, summary = await _dispatch_vertex_handler(vertex, context, state)

            # Complete vertex — fills outgoing edge payloads
            complete_vertex(
                graph, vertex_id,
                result=result,
                result_summary=summary,
                local_context=result,
            )

    except Exception as e:
        logger.error("Vertex %s failed: %s", vertex_id, e, exc_info=True)
        fail_vertex(graph, vertex_id, str(e))

    await report_vertex_completed(graph, vertex_id)

    # --- Impact analysis: propagate changes through artifact graph ---
    # Only for completed non-decompose vertices (decompose vertices create sub-graphs, not changes)
    completed_vertex = graph.vertices.get(vertex_id)
    if (
        completed_vertex
        and completed_vertex.status == VertexStatus.COMPLETED
        and completed_vertex.vertex_type not in (VertexType.PLANNER, VertexType.DECOMPOSE)
        and settings.use_graph_agent  # Only when graph agent is enabled
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

    return {"task_graph": graph.model_dump()}


# ---------------------------------------------------------------------------
# Node: synthesize — compose final result
# ---------------------------------------------------------------------------


async def node_synthesize(state: GraphAgentState) -> dict:
    """Compose the final result from completed vertices."""
    graph_data = state.get("task_graph")
    if not graph_data:
        return {"final_result": state.get("graph_error", "No graph available")}

    graph = TaskGraph(**graph_data)
    graph.status = GraphStatus.COMPLETED
    graph.completed_at = str(int(time.time()))

    result = get_final_result(graph)
    stats = get_stats(graph)

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
        "output. Use tools for coding, KB operations, and other concrete work."
    ),
    VertexType.TASK: (
        "You are the Executor. Complete the assigned task using the provided "
        "context and tools. Be thorough, precise, and produce actionable output."
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
}

# Max agentic loop iterations per vertex (tool call rounds)
_MAX_VERTEX_TOOL_ITERATIONS = 6
# Timeout for a single tool execution within vertex loop
_VERTEX_TOOL_TIMEOUT_S = 60


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
    messages: list[dict] = [
        {"role": "system", "content": system_prompt},
        {
            "role": "user",
            "content": (
                f"## {vertex.title}\n\n{vertex.description}\n\n"
                f"## Context\n{context}"
            ),
        },
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


# Handler dispatch — all types use the unified agentic loop
_VERTEX_HANDLERS: dict[VertexType, Any] = {}


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

