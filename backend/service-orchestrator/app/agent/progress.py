"""Graph progress reporting — emits vertex-level progress to Kotlin server.

Uses the existing kotlin_client.report_progress() API with delegation
fields to communicate graph execution state to the UI.
"""

from __future__ import annotations

import logging

from app.agent.models import (
    GraphStatus,
    GraphType,
    AgentGraph,
    VertexStatus,
)
from app.tools.kotlin_client import kotlin_client

logger = logging.getLogger(__name__)


async def report_vertex_started(
    graph: AgentGraph,
    vertex_id: str,
) -> None:
    """Report that a vertex has started execution."""
    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        return

    completed = sum(
        1 for v in graph.vertices.values()
        if v.status in (VertexStatus.COMPLETED, VertexStatus.FAILED, VertexStatus.SKIPPED)
    )
    total = len(graph.vertices)
    percent = _calc_percent(completed, total)

    fan_in = len(vertex.incoming_context)
    agent_info = f" [{vertex.agent_name}]" if vertex.agent_name else ""
    fan_info = f" (fan-in: {fan_in})" if fan_in > 1 else ""

    await _report(
        graph=graph,
        node=f"vertex_{vertex.vertex_type.value}",
        message=f"⟳ {vertex.title}{agent_info}{fan_info}",
        percent=percent,
        delegation_id=vertex.id,
        delegation_agent=vertex.agent_name,
        delegation_depth=vertex.depth,
    )

    # Notify UI about memory graph change (only for memory_graph graphs)
    if graph.graph_type == GraphType.MEMORY_GRAPH:
        await kotlin_client.notify_memory_graph_changed()

    # Push thinking graph update to chat (for thinking_graph graphs)
    if graph.graph_type == GraphType.THINKING_GRAPH:
        await _push_thinking_graph_update(graph, "vertex_completed", f"⟳ {vertex.title}")


async def report_vertex_completed(
    graph: AgentGraph,
    vertex_id: str,
) -> None:
    """Report that a vertex has completed."""
    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        return

    completed = sum(
        1 for v in graph.vertices.values()
        if v.status in (VertexStatus.COMPLETED, VertexStatus.FAILED, VertexStatus.SKIPPED)
    )
    total = len(graph.vertices)
    percent = _calc_percent(completed, total)

    status_icon = "✓" if vertex.status == VertexStatus.COMPLETED else "✗"
    agent_info = f" [{vertex.agent_name}]" if vertex.agent_name else ""

    await _report(
        graph=graph,
        node=f"vertex_{vertex.vertex_type.value}",
        message=f"{status_icon} {vertex.title}{agent_info} ({completed}/{total})",
        percent=percent,
        delegation_id=vertex.id,
        delegation_agent=vertex.agent_name,
        delegation_depth=vertex.depth,
    )

    # Notify UI about memory graph change (only for memory_graph graphs)
    if graph.graph_type == GraphType.MEMORY_GRAPH:
        await kotlin_client.notify_memory_graph_changed()

    # Push thinking graph update to chat (for thinking_graph graphs)
    if graph.graph_type == GraphType.THINKING_GRAPH:
        await _push_thinking_graph_update(
            graph, "vertex_completed",
            f"{status_icon} {vertex.title} ({completed}/{total})",
        )


async def report_graph_status(
    graph: AgentGraph,
    message: str,
) -> None:
    """Report overall graph status change."""
    percent = 0
    if graph.status == GraphStatus.BUILDING:
        percent = 10
    elif graph.status == GraphStatus.READY:
        percent = 20
    elif graph.status == GraphStatus.EXECUTING:
        completed = sum(
            1 for v in graph.vertices.values()
            if v.status in (VertexStatus.COMPLETED, VertexStatus.FAILED, VertexStatus.SKIPPED)
        )
        percent = _calc_percent(completed, len(graph.vertices))
    elif graph.status in (GraphStatus.COMPLETED, GraphStatus.FAILED):
        percent = 100

    await _report(
        graph=graph,
        node="graph_agent",
        message=message,
        percent=percent,
    )

    # Notify UI about memory graph change (only for memory_graph graphs)
    if graph.graph_type == GraphType.MEMORY_GRAPH:
        await kotlin_client.notify_memory_graph_changed()


async def report_decomposition_progress(
    graph: AgentGraph,
    message: str,
    depth: int = 0,
) -> None:
    """Report progress during decomposition phase."""
    await _report(
        graph=graph,
        node="graph_decompose",
        message=message,
        percent=15,
        delegation_depth=depth,
    )


# ---------------------------------------------------------------------------
# Internal
# ---------------------------------------------------------------------------


def _calc_percent(completed: int, total: int) -> int:
    """Map completion to 20-95 range (leaving room for synthesis)."""
    if total == 0:
        return 20
    return 20 + int(75 * completed / total)


async def _report(
    graph: AgentGraph,
    node: str,
    message: str,
    percent: int,
    delegation_id: str | None = None,
    delegation_agent: str | None = None,
    delegation_depth: int | None = None,
) -> None:
    """Send progress to Kotlin server via existing API."""
    try:
        await kotlin_client.report_progress(
            task_id=graph.task_id,
            client_id=graph.client_id,
            node=node,
            message=message,
            percent=percent,
            delegation_id=delegation_id,
            delegation_agent=delegation_agent,
            delegation_depth=delegation_depth,
        )
    except Exception as e:
        logger.debug("Graph progress report failed: %s", e)


# ---------------------------------------------------------------------------
# Thinking graph push to chat (throttled)
# ---------------------------------------------------------------------------

import time as _time

_thinking_graph_last_push: dict[str, float] = {}
_THINKING_GRAPH_THROTTLE_S = 5.0  # max 1 push per 5s per graph (except terminal)


async def _push_thinking_graph_update(
    graph: AgentGraph,
    status: str,
    message: str,
) -> None:
    """Push thinking graph update to chat via Kotlin.

    Terminal states (started/completed/failed) are always pushed.
    Intermediate updates are throttled to max 1 per 5s per graph.
    """
    is_terminal = status in ("started", "completed", "failed")
    now = _time.monotonic()

    if not is_terminal:
        last = _thinking_graph_last_push.get(graph.id, 0.0)
        if now - last < _THINKING_GRAPH_THROTTLE_S:
            return
    _thinking_graph_last_push[graph.id] = now

    # Get title from root vertex
    root = graph.vertices.get(graph.root_vertex_id)
    title = root.title if root else graph.task_id

    try:
        await kotlin_client.notify_thinking_graph_update(
            task_id=graph.task_id,
            task_title=title,
            graph_id=graph.id,
            status=status,
            message=message,
        )
    except Exception as e:
        logger.debug("Thinking graph push failed: %s", e)
