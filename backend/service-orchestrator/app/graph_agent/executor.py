"""Graph execution engine — processes vertices in topological order.

Executes the task graph by:
1. Finding READY vertices (all incoming edges have payloads)
2. Executing them (in parallel when independent)
3. Filling outgoing edge payloads with results
4. Repeating until all vertices are done

Context flows through edges: each vertex receives accumulated context
from all upstream vertices via incoming edge payloads.

Integrates with:
- Existing BaseAgent/AgentRegistry for TASK vertex execution
- llm_with_cloud_fallback for SYNTHESIS/GATE vertices
- Graph progress reporting for UI updates
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import TYPE_CHECKING

from app.config import settings
from app.graph.nodes._helpers import llm_with_cloud_fallback, parse_json_response
from app.graph_agent.decomposer import decompose_vertex
from app.graph_agent.graph import (
    complete_vertex,
    fail_vertex,
    get_ready_vertices,
    start_vertex,
    topological_order,
)
from app.graph_agent.models import (
    GraphStatus,
    GraphVertex,
    TaskGraph,
    VertexStatus,
    VertexType,
)
from app.graph_agent.persistence import task_graph_store
from app.graph_agent.progress import (
    report_graph_status,
    report_vertex_completed,
    report_vertex_started,
)
from app.models import AgentOutput, DelegationMessage

if TYPE_CHECKING:
    from app.agents.registry import AgentRegistry

logger = logging.getLogger(__name__)

# Max parallel vertex executions
MAX_PARALLEL_VERTICES = 5
# Vertex execution timeout (seconds)
VERTEX_TIMEOUT = 300


async def execute_graph(
    graph: TaskGraph,
    state: dict,
    registry: "AgentRegistry | None" = None,
    guidelines: str = "",
) -> TaskGraph:
    """Execute all vertices in topological order with fan-in support.

    Main execution loop:
    1. Find READY vertices (all incoming edges satisfied)
    2. Handle DECOMPOSE vertices (recursive decomposition)
    3. Execute TASK/SYNTHESIS/GATE vertices in parallel batches
    4. Fill outgoing edge payloads with results
    5. Repeat until no more READY vertices

    Returns the modified graph with all results.
    """
    graph.status = GraphStatus.EXECUTING
    await task_graph_store.save(graph)
    await report_graph_status(graph, "Starting graph execution…")

    iteration = 0
    max_iterations = len(graph.vertices) * 2  # Safety limit

    while iteration < max_iterations:
        iteration += 1

        # Find vertices ready to execute
        ready = get_ready_vertices(graph)
        if not ready:
            # No ready vertices — check if we're done or stuck
            if _all_terminal(graph):
                break
            logger.warning(
                "No ready vertices but graph not complete (iteration %d)",
                iteration,
            )
            break

        # Separate DECOMPOSE vertices (must be handled before execution)
        decompose_vertices = [v for v in ready if v.vertex_type == VertexType.DECOMPOSE]
        exec_vertices = [v for v in ready if v.vertex_type != VertexType.DECOMPOSE]

        # Handle DECOMPOSE vertices first (they add new vertices to the graph)
        for dv in decompose_vertices:
            try:
                start_vertex(graph, dv.id)
                await report_vertex_started(graph, dv.id)
                graph = await decompose_vertex(
                    graph, dv.id, state, guidelines,
                )
                await task_graph_store.save(graph)
            except Exception as e:
                logger.error("Decomposition failed for %s: %s", dv.id, e)
                fail_vertex(graph, dv.id, str(e))
                await task_graph_store.save(graph)

        # Execute TASK/SYNTHESIS/GATE vertices in parallel batches
        if exec_vertices:
            # Batch to limit concurrency
            for batch_start in range(0, len(exec_vertices), MAX_PARALLEL_VERTICES):
                batch = exec_vertices[batch_start:batch_start + MAX_PARALLEL_VERTICES]
                await _execute_batch(graph, batch, state, registry)
                await task_graph_store.save(graph)

    # Final status
    if graph.status != GraphStatus.COMPLETED:
        if any(v.status == VertexStatus.FAILED for v in graph.vertices.values()):
            graph.status = GraphStatus.FAILED
        elif _all_terminal(graph):
            graph.status = GraphStatus.COMPLETED
        graph.completed_at = str(int(time.time()))

    await task_graph_store.save(graph)
    await report_graph_status(
        graph,
        "Graph execution completed" if graph.status == GraphStatus.COMPLETED
        else "Graph execution finished with failures",
    )

    return graph


async def _execute_batch(
    graph: TaskGraph,
    vertices: list[GraphVertex],
    state: dict,
    registry: "AgentRegistry | None",
) -> None:
    """Execute a batch of vertices in parallel."""
    if len(vertices) == 1:
        # Single vertex — no gather overhead
        await _execute_single(graph, vertices[0], state, registry)
        return

    coros = [
        _execute_single(graph, v, state, registry)
        for v in vertices
    ]
    await asyncio.gather(*coros, return_exceptions=True)


async def _execute_single(
    graph: TaskGraph,
    vertex: GraphVertex,
    state: dict,
    registry: "AgentRegistry | None",
) -> None:
    """Execute a single vertex based on its type."""
    vertex = start_vertex(graph, vertex.id)
    if not vertex:
        return

    await report_vertex_started(graph, vertex.id)

    try:
        if vertex.vertex_type == VertexType.TASK:
            await _execute_task_vertex(graph, vertex, state, registry)
        elif vertex.vertex_type == VertexType.SYNTHESIS:
            await _execute_synthesis_vertex(graph, vertex, state)
        elif vertex.vertex_type == VertexType.GATE:
            await _execute_gate_vertex(graph, vertex, state)
        elif vertex.vertex_type == VertexType.ROOT:
            # Root already handled by decompose_root
            complete_vertex(
                graph, vertex.id,
                result="Root vertex",
                result_summary="Root vertex completed",
            )
        else:
            logger.warning("Unknown vertex type: %s", vertex.vertex_type)
            fail_vertex(graph, vertex.id, f"Unknown vertex type: {vertex.vertex_type}")

    except asyncio.TimeoutError:
        fail_vertex(
            graph, vertex.id,
            f"Timed out after {VERTEX_TIMEOUT}s",
        )
    except Exception as e:
        logger.error(
            "Vertex %s (%s) failed: %s", vertex.id, vertex.title, e,
            exc_info=True,
        )
        fail_vertex(graph, vertex.id, str(e))

    await report_vertex_completed(graph, vertex.id)


# ---------------------------------------------------------------------------
# Vertex type handlers
# ---------------------------------------------------------------------------


async def _execute_task_vertex(
    graph: TaskGraph,
    vertex: GraphVertex,
    state: dict,
    registry: "AgentRegistry | None",
) -> None:
    """Execute a TASK vertex by dispatching to an agent.

    If an agent is assigned and the registry is available, dispatch
    to the specialist agent. Otherwise, use LLM directly.
    """
    # Build context from incoming edges
    context = _build_vertex_context(vertex)

    if registry and vertex.agent_name:
        # Dispatch to specialist agent
        agent = registry.get(vertex.agent_name) or registry.get("legacy")
        if agent:
            msg = DelegationMessage(
                delegation_id=vertex.id,
                depth=vertex.depth,
                agent_name=vertex.agent_name,
                task_summary=vertex.description,
                context=context,
                expected_output="Complete the task and provide results",
                response_language=state.get("response_language", "en"),
                client_id=graph.client_id,
                project_id=graph.project_id,
            )
            output: AgentOutput = await asyncio.wait_for(
                agent.execute(msg, state),
                timeout=VERTEX_TIMEOUT,
            )
            complete_vertex(
                graph, vertex.id,
                result=output.result,
                result_summary=output.result[:500],
                local_context=output.result,
                tools_used=[],
                token_count=0,
            )
            return

    # Fallback: LLM direct execution
    await _llm_execute_vertex(graph, vertex, state, context)


async def _execute_synthesis_vertex(
    graph: TaskGraph,
    vertex: GraphVertex,
    state: dict,
) -> None:
    """Execute a SYNTHESIS vertex by combining upstream results via LLM.

    Receives all incoming edge payloads and asks LLM to merge them
    into a coherent result.
    """
    if not vertex.incoming_context:
        complete_vertex(
            graph, vertex.id,
            result="No upstream results to synthesize",
            result_summary="No results",
        )
        return

    # Build synthesis prompt from all incoming contexts
    parts = []
    for payload in vertex.incoming_context:
        parts.append(
            f"### {payload.source_vertex_title}\n"
            f"**Summary:** {payload.summary}\n"
            f"**Details:**\n{payload.context[:3000]}"
        )
    upstream_text = "\n\n---\n\n".join(parts)

    system_prompt = (
        "You are a synthesis agent. Combine multiple results into a coherent, "
        "unified response. Preserve key details, resolve contradictions, and "
        "acknowledge any failures.\n\n"
        f"Task context: {vertex.description}"
    )

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": f"## Upstream Results\n\n{upstream_text}\n\n"
         f"Combine these into a final coherent result."},
    ]

    response = await llm_with_cloud_fallback(
        state=state,
        messages=messages,
        task_type="synthesis",
        max_tokens=settings.default_output_tokens,
    )
    result = response.choices[0].message.content or ""

    complete_vertex(
        graph, vertex.id,
        result=result,
        result_summary=result[:500],
        local_context=result,
    )


async def _execute_gate_vertex(
    graph: TaskGraph,
    vertex: GraphVertex,
    state: dict,
) -> None:
    """Execute a GATE vertex — decision point.

    Uses LLM to evaluate upstream results and decide whether to
    proceed, skip, or modify the downstream path.
    """
    context = _build_vertex_context(vertex)

    system_prompt = (
        "You are a decision gate. Based on upstream results, evaluate "
        "whether the task should proceed. Respond with JSON:\n"
        '{"proceed": true/false, "reason": "...", "summary": "..."}'
    )

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": f"## Gate: {vertex.description}\n\n"
         f"## Upstream Context\n{context}"},
    ]

    response = await llm_with_cloud_fallback(
        state=state,
        messages=messages,
        task_type="gate",
        max_tokens=1024,
    )
    content = response.choices[0].message.content or ""
    parsed = parse_json_response(content)

    proceed = parsed.get("proceed", True)
    reason = parsed.get("reason", "")
    summary = parsed.get("summary", content[:500])

    if proceed:
        complete_vertex(
            graph, vertex.id,
            result=f"Gate passed: {reason}",
            result_summary=summary,
            local_context=content,
        )
    else:
        # Gate blocked — mark as failed, downstream will be skipped
        fail_vertex(graph, vertex.id, f"Gate blocked: {reason}")


# ---------------------------------------------------------------------------
# LLM direct execution (fallback when no agent assigned)
# ---------------------------------------------------------------------------


async def _llm_execute_vertex(
    graph: TaskGraph,
    vertex: GraphVertex,
    state: dict,
    context: str,
) -> None:
    """Execute a vertex directly via LLM (no agent dispatch)."""
    system_prompt = (
        "You are a task execution agent. Complete the assigned task "
        "using the provided context. Be thorough but concise."
    )

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": f"## Task: {vertex.title}\n\n"
         f"{vertex.description}\n\n"
         f"## Context\n{context}"},
    ]

    response = await llm_with_cloud_fallback(
        state=state,
        messages=messages,
        task_type="execution",
        max_tokens=settings.default_output_tokens,
    )
    result = response.choices[0].message.content or ""

    complete_vertex(
        graph, vertex.id,
        result=result,
        result_summary=result[:500],
        local_context=result,
    )


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _build_vertex_context(vertex: GraphVertex) -> str:
    """Build context string from incoming edge payloads.

    Each incoming payload provides a summary + full searchable context
    from its source vertex.
    """
    if not vertex.incoming_context:
        return vertex.input_request

    parts = [f"## Input Request\n{vertex.input_request}"]

    for payload in vertex.incoming_context:
        parts.append(
            f"\n## From: {payload.source_vertex_title}\n"
            f"Summary: {payload.summary}\n"
            f"Context:\n{payload.context}"
        )

    return "\n".join(parts)


def _all_terminal(graph: TaskGraph) -> bool:
    """Check if all vertices are in a terminal state."""
    terminal = {VertexStatus.COMPLETED, VertexStatus.FAILED, VertexStatus.SKIPPED}
    return all(v.status in terminal for v in graph.vertices.values())
