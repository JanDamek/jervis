"""TaskGraph operations — traversal, context accumulation, readiness detection.

This module provides the core graph engine:
- add/remove vertices and edges
- topological sort for execution ordering
- fan-in detection (all incoming edges must have payloads)
- context accumulation (gather all upstream payloads)
- vertex completion (fill outgoing edge payloads)
- cycle detection
"""

from __future__ import annotations

import logging
import time
import uuid
from collections import deque

from app.config import estimate_tokens
from app.graph_agent.models import (
    EdgePayload,
    EdgeType,
    GraphEdge,
    GraphStatus,
    GraphType,
    GraphVertex,
    TaskGraph,
    VertexStatus,
    VertexType,
)

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Factory
# ---------------------------------------------------------------------------


def create_task_graph(
    task_id: str,
    client_id: str,
    project_id: str | None,
    root_title: str,
    root_description: str,
) -> TaskGraph:
    """Create a new task sub-graph with a root vertex.

    Client isolation: client_id is set on the graph and used for KB access
    scoping during vertex execution. Cross-client edges are impossible
    because all vertices live in the same graph document.
    """
    graph_id = f"tg-{task_id}-{uuid.uuid4().hex[:8]}"
    root_id = f"v-root-{uuid.uuid4().hex[:8]}"

    root = GraphVertex(
        id=root_id,
        title=root_title,
        description=root_description,
        vertex_type=VertexType.ROOT,
        status=VertexStatus.READY,  # Root has no incoming edges
        input_request=root_description,
        depth=0,
    )

    return TaskGraph(
        id=graph_id,
        task_id=task_id,
        client_id=client_id,
        project_id=project_id,
        root_vertex_id=root_id,
        vertices={root_id: root},
        created_at=str(int(time.time())),
    )


# ---------------------------------------------------------------------------
# Vertex operations
# ---------------------------------------------------------------------------


def add_vertex(
    graph: TaskGraph,
    title: str,
    description: str,
    vertex_type: VertexType = VertexType.TASK,
    agent_name: str | None = None,
    parent_id: str | None = None,
    input_request: str = "",
) -> GraphVertex:
    """Add a new vertex to the graph. Returns the created vertex."""
    depth = 0
    if parent_id and parent_id in graph.vertices:
        depth = graph.vertices[parent_id].depth + 1

    vertex = GraphVertex(
        id=f"v-{uuid.uuid4().hex[:12]}",
        title=title,
        description=description,
        vertex_type=vertex_type,
        agent_name=agent_name,
        parent_id=parent_id,
        depth=depth,
        input_request=input_request or description,
    )
    graph.vertices[vertex.id] = vertex
    return vertex


def remove_vertex(graph: TaskGraph, vertex_id: str) -> bool:
    """Remove a vertex and all its edges. Returns True if found."""
    if vertex_id not in graph.vertices:
        return False
    if vertex_id == graph.root_vertex_id:
        raise ValueError("Cannot remove root vertex")

    del graph.vertices[vertex_id]
    graph.edges = [
        e for e in graph.edges
        if e.source_id != vertex_id and e.target_id != vertex_id
    ]
    return True


# ---------------------------------------------------------------------------
# Edge operations
# ---------------------------------------------------------------------------


def add_edge(
    graph: TaskGraph,
    source_id: str,
    target_id: str,
    edge_type: EdgeType = EdgeType.DEPENDENCY,
) -> GraphEdge:
    """Add a directed edge from source to target. Returns the created edge.

    Note: client isolation is enforced at the graph level (TaskGraph.client_id),
    not per-vertex. All vertices in a single graph belong to the same client.
    Cross-graph edges are not supported — sub-graphs link via TASK_REF vertices.
    """
    if source_id not in graph.vertices:
        raise ValueError(f"Source vertex {source_id} not in graph")
    if target_id not in graph.vertices:
        raise ValueError(f"Target vertex {target_id} not in graph")
    if source_id == target_id:
        raise ValueError("Self-loops not allowed")

    edge = GraphEdge(
        id=f"e-{uuid.uuid4().hex[:12]}",
        source_id=source_id,
        target_id=target_id,
        edge_type=edge_type,
    )
    graph.edges.append(edge)

    # Recalculate target readiness
    _update_vertex_readiness(graph, target_id)

    return edge


def remove_edge(graph: TaskGraph, edge_id: str) -> bool:
    """Remove an edge by ID. Returns True if found."""
    before = len(graph.edges)
    graph.edges = [e for e in graph.edges if e.id != edge_id]
    return len(graph.edges) < before


# ---------------------------------------------------------------------------
# Query operations
# ---------------------------------------------------------------------------


def get_incoming_edges(graph: TaskGraph, vertex_id: str) -> list[GraphEdge]:
    """Get all edges pointing TO this vertex."""
    return [e for e in graph.edges if e.target_id == vertex_id]


def get_outgoing_edges(graph: TaskGraph, vertex_id: str) -> list[GraphEdge]:
    """Get all edges going FROM this vertex."""
    return [e for e in graph.edges if e.source_id == vertex_id]


def get_fan_in_count(graph: TaskGraph, vertex_id: str) -> int:
    """Number of incoming edges to a vertex."""
    return len(get_incoming_edges(graph, vertex_id))


def get_fan_out_count(graph: TaskGraph, vertex_id: str) -> int:
    """Number of outgoing edges from a vertex."""
    return len(get_outgoing_edges(graph, vertex_id))


def get_children(graph: TaskGraph, vertex_id: str) -> list[GraphVertex]:
    """Get vertices that were decomposed from this vertex (parent_id match)."""
    return [v for v in graph.vertices.values() if v.parent_id == vertex_id]


def get_vertex(graph: TaskGraph, vertex_id: str) -> GraphVertex | None:
    """Get a vertex by ID."""
    return graph.vertices.get(vertex_id)


# ---------------------------------------------------------------------------
# Readiness & context accumulation
# ---------------------------------------------------------------------------


def get_ready_vertices(graph: TaskGraph) -> list[GraphVertex]:
    """Get all vertices that are READY for execution.

    A vertex is READY when:
    - It has no incoming edges (source vertex), OR
    - ALL incoming edges have payloads (all upstream completed)
    AND
    - Its current status is PENDING or READY
    """
    ready = []
    for vertex in graph.vertices.values():
        if vertex.status not in (VertexStatus.PENDING, VertexStatus.READY):
            continue
        incoming = get_incoming_edges(graph, vertex.id)
        if not incoming:
            # No dependencies — always ready
            vertex.status = VertexStatus.READY
            ready.append(vertex)
        elif all(e.payload is not None for e in incoming):
            vertex.status = VertexStatus.READY
            ready.append(vertex)
    return ready


def accumulate_context(graph: TaskGraph, vertex_id: str) -> list[EdgePayload]:
    """Gather all incoming edge payloads for a vertex.

    Returns the list of EdgePayloads from all incoming edges.
    If 10 edges converge here, returns 10 payloads (each with
    summary + full context from its source vertex).
    """
    incoming = get_incoming_edges(graph, vertex_id)
    return [e.payload for e in incoming if e.payload is not None]


# ---------------------------------------------------------------------------
# Vertex lifecycle
# ---------------------------------------------------------------------------


def start_vertex(graph: TaskGraph, vertex_id: str) -> GraphVertex | None:
    """Mark a vertex as RUNNING. Populates incoming_context from edges."""
    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        return None
    if vertex.status not in (VertexStatus.READY, VertexStatus.PENDING):
        logger.warning(
            "Cannot start vertex %s in status %s", vertex_id, vertex.status,
        )
        return None

    # Accumulate context from all incoming edges
    vertex.incoming_context = accumulate_context(graph, vertex_id)
    vertex.status = VertexStatus.RUNNING
    vertex.started_at = str(int(time.time()))
    graph.status = GraphStatus.EXECUTING
    return vertex


def complete_vertex(
    graph: TaskGraph,
    vertex_id: str,
    result: str,
    result_summary: str,
    local_context: str = "",
    tools_used: list[str] | None = None,
    token_count: int = 0,
    llm_calls: int = 0,
) -> GraphVertex | None:
    """Mark a vertex as COMPLETED and fill outgoing edge payloads.

    After completion, each outgoing edge gets a payload with:
    - summary: the result_summary
    - context: the local_context (full, searchable)

    Then downstream vertices are checked for readiness.
    """
    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        return None

    vertex.status = VertexStatus.COMPLETED
    vertex.result = result
    vertex.result_summary = result_summary
    vertex.local_context = local_context or result
    vertex.completed_at = str(int(time.time()))
    vertex.token_count = token_count
    vertex.llm_calls = llm_calls
    if tools_used:
        vertex.tools_used = tools_used

    # Update graph-level counters
    graph.total_token_count += token_count
    graph.total_llm_calls += llm_calls

    # Fill outgoing edge payloads
    payload = EdgePayload(
        source_vertex_id=vertex.id,
        source_vertex_title=vertex.title,
        summary=result_summary,
        context=vertex.local_context,
    )
    for edge in get_outgoing_edges(graph, vertex_id):
        edge.payload = payload

    # Update readiness of downstream vertices
    for edge in get_outgoing_edges(graph, vertex_id):
        _update_vertex_readiness(graph, edge.target_id)

    # Check if entire graph is complete
    _check_graph_completion(graph)

    return vertex


def fail_vertex(
    graph: TaskGraph,
    vertex_id: str,
    error: str,
) -> GraphVertex | None:
    """Mark a vertex as FAILED."""
    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        return None

    vertex.status = VertexStatus.FAILED
    vertex.error = error
    vertex.completed_at = str(int(time.time()))

    # Propagate failure: mark unreachable downstream vertices as SKIPPED
    _propagate_failure(graph, vertex_id)

    _check_graph_completion(graph)
    return vertex


def skip_vertex(graph: TaskGraph, vertex_id: str) -> GraphVertex | None:
    """Mark a vertex as SKIPPED (e.g. conditional branch not taken)."""
    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        return None

    vertex.status = VertexStatus.SKIPPED
    vertex.completed_at = str(int(time.time()))
    return vertex


# ---------------------------------------------------------------------------
# Topological sort
# ---------------------------------------------------------------------------


def topological_order(graph: TaskGraph) -> list[str]:
    """Return vertex IDs in topological execution order (Kahn's algorithm).

    Raises ValueError if the graph has a cycle.
    """
    in_degree: dict[str, int] = {vid: 0 for vid in graph.vertices}
    for edge in graph.edges:
        if edge.target_id in in_degree:
            in_degree[edge.target_id] += 1

    queue: deque[str] = deque()
    for vid, degree in in_degree.items():
        if degree == 0:
            queue.append(vid)

    order: list[str] = []
    while queue:
        vid = queue.popleft()
        order.append(vid)
        for edge in get_outgoing_edges(graph, vid):
            if edge.target_id in in_degree:
                in_degree[edge.target_id] -= 1
                if in_degree[edge.target_id] == 0:
                    queue.append(edge.target_id)

    if len(order) != len(graph.vertices):
        raise ValueError(
            f"Graph has a cycle: processed {len(order)} of "
            f"{len(graph.vertices)} vertices"
        )

    return order


def has_cycle(graph: TaskGraph) -> bool:
    """Check if the graph contains a cycle."""
    try:
        topological_order(graph)
        return False
    except ValueError:
        return True


# ---------------------------------------------------------------------------
# Graph statistics
# ---------------------------------------------------------------------------


def get_stats(graph: TaskGraph) -> dict:
    """Get graph statistics."""
    statuses = {}
    for v in graph.vertices.values():
        statuses[v.status.value] = statuses.get(v.status.value, 0) + 1

    return {
        "total_vertices": len(graph.vertices),
        "total_edges": len(graph.edges),
        "max_depth": max((v.depth for v in graph.vertices.values()), default=0),
        "vertex_statuses": statuses,
        "total_tokens": graph.total_token_count,
        "total_llm_calls": graph.total_llm_calls,
        "is_complete": _is_graph_complete(graph),
        "has_failures": any(
            v.status == VertexStatus.FAILED for v in graph.vertices.values()
        ),
    }


def get_final_result(graph: TaskGraph) -> str:
    """Compose the final result from completed vertices.

    Collects result_summary from all completed leaf vertices
    (vertices with no outgoing edges or whose outgoing edges
    lead to a SYNTHESIS vertex).
    """
    if not _is_graph_complete(graph):
        return ""

    # Find terminal vertices (no outgoing edges)
    terminal_ids = set()
    for vid in graph.vertices:
        outgoing = get_outgoing_edges(graph, vid)
        if not outgoing:
            terminal_ids.add(vid)

    # Collect results from terminal vertices
    results = []
    for vid in terminal_ids:
        vertex = graph.vertices[vid]
        if vertex.status == VertexStatus.COMPLETED and vertex.result:
            results.append(vertex.result)

    # If only one terminal, return its result
    if len(results) == 1:
        return results[0]

    # Multiple terminals — concatenate with headers
    parts = []
    for vid in terminal_ids:
        vertex = graph.vertices[vid]
        if vertex.status == VertexStatus.COMPLETED and vertex.result:
            parts.append(f"## {vertex.title}\n{vertex.result}")
    return "\n\n".join(parts)


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _update_vertex_readiness(graph: TaskGraph, vertex_id: str) -> None:
    """Recalculate readiness of a vertex based on incoming edges."""
    vertex = graph.vertices.get(vertex_id)
    if not vertex or vertex.status not in (VertexStatus.PENDING, VertexStatus.READY):
        return

    incoming = get_incoming_edges(graph, vertex_id)
    if not incoming:
        vertex.status = VertexStatus.READY
    elif all(e.payload is not None for e in incoming):
        vertex.status = VertexStatus.READY
    else:
        vertex.status = VertexStatus.PENDING


def _propagate_failure(graph: TaskGraph, failed_vertex_id: str) -> None:
    """Mark downstream vertices as SKIPPED if they can't execute.

    A downstream vertex is skipped if ALL its incoming paths go through
    the failed vertex (i.e. there's no alternative path).
    """
    visited: set[str] = set()
    queue: deque[str] = deque()

    for edge in get_outgoing_edges(graph, failed_vertex_id):
        queue.append(edge.target_id)

    while queue:
        vid = queue.popleft()
        if vid in visited:
            continue
        visited.add(vid)

        vertex = graph.vertices.get(vid)
        if not vertex or vertex.status in (
            VertexStatus.COMPLETED, VertexStatus.RUNNING,
        ):
            continue

        # Check if ALL incoming edges come from failed/skipped sources
        incoming = get_incoming_edges(graph, vid)
        all_blocked = all(
            graph.vertices.get(e.source_id) is not None
            and graph.vertices[e.source_id].status in (
                VertexStatus.FAILED, VertexStatus.SKIPPED,
            )
            for e in incoming
        )

        if all_blocked:
            vertex.status = VertexStatus.SKIPPED
            for edge in get_outgoing_edges(graph, vid):
                queue.append(edge.target_id)


def _is_graph_complete(graph: TaskGraph) -> bool:
    """Check if all vertices are in a terminal state."""
    terminal = {VertexStatus.COMPLETED, VertexStatus.FAILED, VertexStatus.SKIPPED}
    return all(v.status in terminal for v in graph.vertices.values())


def _check_graph_completion(graph: TaskGraph) -> None:
    """Update graph status if all vertices are done.

    Master maps are never "completed" — they grow with each interaction.
    """
    if graph.graph_type == GraphType.MASTER:
        return  # Master map never completes
    if _is_graph_complete(graph):
        has_failures = any(
            v.status == VertexStatus.FAILED for v in graph.vertices.values()
        )
        graph.status = GraphStatus.FAILED if has_failures else GraphStatus.COMPLETED
        graph.completed_at = str(int(time.time()))


# ---------------------------------------------------------------------------
# Master Map operations
# ---------------------------------------------------------------------------


def create_master_graph(client_id: str = "") -> TaskGraph:
    """Create a new master map (global singleton).

    The master map is a persistent graph that holds all chat interactions
    and references to task sub-graphs. It never completes.
    """
    graph_id = f"master-{uuid.uuid4().hex[:8]}"
    root_id = f"v-master-root-{uuid.uuid4().hex[:8]}"

    root = GraphVertex(
        id=root_id,
        title="Master Map",
        description="Global thinking map — all interactions and task references",
        vertex_type=VertexType.ROOT,
        status=VertexStatus.COMPLETED,  # Root is always done
        depth=0,
    )

    return TaskGraph(
        id=graph_id,
        task_id="master",
        client_id=client_id,
        graph_type=GraphType.MASTER,
        root_vertex_id=root_id,
        vertices={root_id: root},
        status=GraphStatus.EXECUTING,  # Master is always "executing"
        created_at=str(int(time.time())),
    )


def add_chat_vertex(
    graph: TaskGraph,
    message: str,
    response: str,
    response_summary: str = "",
) -> GraphVertex:
    """Add a CHAT_EXCHANGE vertex to the master map.

    Records a chat message→response pair. The response_summary is stored
    as result_summary for compact master map summaries.
    """
    vertex = GraphVertex(
        id=f"v-chat-{uuid.uuid4().hex[:12]}",
        title=message[:80] if message else "Chat",
        description=message,
        vertex_type=VertexType.CHAT_EXCHANGE,
        status=VertexStatus.COMPLETED,
        result=response,
        result_summary=response_summary or response[:200],
        completed_at=str(int(time.time())),
        depth=1,
    )
    graph.vertices[vertex.id] = vertex
    return vertex


def add_task_ref_vertex(
    graph: TaskGraph,
    task_id: str,
    sub_graph_id: str,
    title: str,
) -> GraphVertex:
    """Add a TASK_REF vertex linking to a task sub-graph.

    The vertex tracks the lifecycle of a background task. Its status
    mirrors the sub-graph status (RUNNING while active, COMPLETED when done).
    """
    vertex = GraphVertex(
        id=f"v-taskref-{uuid.uuid4().hex[:12]}",
        title=title,
        description=f"Background task {task_id} → sub-graph {sub_graph_id}",
        vertex_type=VertexType.TASK_REF,
        status=VertexStatus.RUNNING,
        input_request=task_id,           # Store task_id in input_request
        local_context=sub_graph_id,      # Store sub_graph_id in local_context
        started_at=str(int(time.time())),
        depth=1,
    )
    graph.vertices[vertex.id] = vertex
    return vertex


def create_ask_user_vertex(
    graph: TaskGraph,
    question: str,
    context: str = "",
    parent_vertex_id: str | None = None,
) -> GraphVertex:
    """Create an ASK_USER vertex (BLOCKED — waiting for user input).

    The graph pauses at this vertex. When the user answers (via chat),
    resume_vertex() fills the result and unblocks downstream processing.
    """
    depth = 1
    if parent_vertex_id and parent_vertex_id in graph.vertices:
        depth = graph.vertices[parent_vertex_id].depth + 1

    vertex = GraphVertex(
        id=f"v-ask-{uuid.uuid4().hex[:12]}",
        title=f"Question: {question[:60]}",
        description=question,
        vertex_type=VertexType.ASK_USER,
        status=VertexStatus.BLOCKED,
        input_request=question,
        local_context=context,
        parent_id=parent_vertex_id,
        depth=depth,
        started_at=str(int(time.time())),
    )
    graph.vertices[vertex.id] = vertex
    return vertex


def block_vertex(graph: TaskGraph, vertex_id: str, reason: str = "") -> GraphVertex | None:
    """Block a running vertex (e.g. waiting for user input)."""
    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        return None
    vertex.status = VertexStatus.BLOCKED
    if reason:
        vertex.error = reason
    return vertex


def resume_vertex(
    graph: TaskGraph,
    vertex_id: str,
    answer: str,
) -> GraphVertex | None:
    """Resume a BLOCKED vertex with the user's answer.

    Marks the vertex as COMPLETED, fills result/summary, and propagates
    to downstream edges so dependent vertices become READY.
    """
    vertex = graph.vertices.get(vertex_id)
    if not vertex or vertex.status != VertexStatus.BLOCKED:
        logger.warning("Cannot resume vertex %s (status=%s)",
                       vertex_id, vertex.status if vertex else "missing")
        return None

    return complete_vertex(
        graph, vertex_id,
        result=answer,
        result_summary=answer[:200],
    )


def find_blocked_vertices(graph: TaskGraph) -> list[GraphVertex]:
    """Find all BLOCKED (ASK_USER) vertices in the graph."""
    return [
        v for v in graph.vertices.values()
        if v.status == VertexStatus.BLOCKED
    ]


def master_map_summary(graph: TaskGraph, max_tokens: int = 2000) -> str:
    """Generate a compact summary of the master map for LLM context injection.

    Budget: max 2000 tokens. Includes:
    - Last N chat vertices (title + status)
    - Active task refs (task_id + status)
    - BLOCKED vertices (what's waiting for answer)

    Fits into 48k GPU context with room for vertex work.
    """
    parts: list[str] = []
    token_count = 0

    # 1. BLOCKED vertices (highest priority — user needs to act)
    blocked = find_blocked_vertices(graph)
    if blocked:
        parts.append("## Waiting for your answer:")
        for v in blocked:
            line = f"- [{v.id}] {v.description}"
            parts.append(line)

    # 2. Active task refs
    active_refs = [
        v for v in graph.vertices.values()
        if v.vertex_type == VertexType.TASK_REF
        and v.status in (VertexStatus.RUNNING, VertexStatus.BLOCKED)
    ]
    if active_refs:
        parts.append("\n## Active tasks:")
        for v in active_refs:
            line = f"- [{v.input_request}] {v.title} ({v.status.value})"
            parts.append(line)

    # 3. Recent chat exchanges (newest first, up to budget)
    chats = sorted(
        [v for v in graph.vertices.values()
         if v.vertex_type == VertexType.CHAT_EXCHANGE],
        key=lambda v: v.completed_at or "0",
        reverse=True,
    )
    if chats:
        parts.append("\n## Recent context:")
        for v in chats:
            line = f"- {v.title}: {v.result_summary}"
            token_count += estimate_tokens(line)
            if token_count > max_tokens:
                parts.append(f"- ... ({len(chats)} total exchanges)")
                break
            parts.append(line)

    # 4. Completed task refs (brief)
    done_refs = [
        v for v in graph.vertices.values()
        if v.vertex_type == VertexType.TASK_REF
        and v.status == VertexStatus.COMPLETED
    ]
    if done_refs:
        remaining = max_tokens - token_count
        if remaining > 100:
            parts.append("\n## Completed tasks:")
            for v in done_refs[-5:]:  # Last 5
                line = f"- {v.title} (done)"
                parts.append(line)

    return "\n".join(parts) if parts else ""
