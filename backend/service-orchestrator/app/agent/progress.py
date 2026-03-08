"""Graph progress reporting — emits vertex-level progress to Kotlin server.

Uses the existing kotlin_client.report_progress() API with delegation
fields to communicate graph execution state to the UI.
"""

from __future__ import annotations

import logging

from app.agent.models import (
    GraphStatus,
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
