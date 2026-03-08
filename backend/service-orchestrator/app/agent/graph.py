"""AgentGraph operations — traversal, context accumulation, readiness detection.

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
import uuid
from collections import deque
from datetime import datetime, timezone

from app.config import estimate_tokens
from app.agent.models import (
    EdgePayload,
    EdgeType,
    GraphEdge,
    GraphStatus,
    GraphType,
    GraphVertex,
    AgentGraph,
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
) -> AgentGraph:
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

    return AgentGraph(
        id=graph_id,
        task_id=task_id,
        client_id=client_id,
        project_id=project_id,
        root_vertex_id=root_id,
        vertices={root_id: root},
        created_at=datetime.now(timezone.utc).isoformat(),
    )


# ---------------------------------------------------------------------------
# Vertex operations
# ---------------------------------------------------------------------------


def add_vertex(
    graph: AgentGraph,
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


def remove_vertex(graph: AgentGraph, vertex_id: str) -> bool:
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
    graph: AgentGraph,
    source_id: str,
    target_id: str,
    edge_type: EdgeType = EdgeType.DEPENDENCY,
) -> GraphEdge:
    """Add a directed edge from source to target. Returns the created edge.

    Note: client isolation is enforced at the graph level (AgentGraph.client_id),
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


def remove_edge(graph: AgentGraph, edge_id: str) -> bool:
    """Remove an edge by ID. Returns True if found."""
    before = len(graph.edges)
    graph.edges = [e for e in graph.edges if e.id != edge_id]
    return len(graph.edges) < before


# ---------------------------------------------------------------------------
# Query operations
# ---------------------------------------------------------------------------


def get_incoming_edges(graph: AgentGraph, vertex_id: str) -> list[GraphEdge]:
    """Get all edges pointing TO this vertex."""
    return [e for e in graph.edges if e.target_id == vertex_id]


def get_outgoing_edges(graph: AgentGraph, vertex_id: str) -> list[GraphEdge]:
    """Get all edges going FROM this vertex."""
    return [e for e in graph.edges if e.source_id == vertex_id]


def get_fan_in_count(graph: AgentGraph, vertex_id: str) -> int:
    """Number of incoming edges to a vertex."""
    return len(get_incoming_edges(graph, vertex_id))


def get_fan_out_count(graph: AgentGraph, vertex_id: str) -> int:
    """Number of outgoing edges from a vertex."""
    return len(get_outgoing_edges(graph, vertex_id))


def get_children(graph: AgentGraph, vertex_id: str) -> list[GraphVertex]:
    """Get vertices that were decomposed from this vertex (parent_id match)."""
    return [v for v in graph.vertices.values() if v.parent_id == vertex_id]


def get_vertex(graph: AgentGraph, vertex_id: str) -> GraphVertex | None:
    """Get a vertex by ID."""
    return graph.vertices.get(vertex_id)


# ---------------------------------------------------------------------------
# Readiness & context accumulation
# ---------------------------------------------------------------------------


def get_ready_vertices(graph: AgentGraph) -> list[GraphVertex]:
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


def accumulate_context(graph: AgentGraph, vertex_id: str) -> list[EdgePayload]:
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


def start_vertex(graph: AgentGraph, vertex_id: str) -> GraphVertex | None:
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
    vertex.started_at = datetime.now(timezone.utc).isoformat()
    graph.status = GraphStatus.EXECUTING
    return vertex


def complete_vertex(
    graph: AgentGraph,
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
    vertex.completed_at = datetime.now(timezone.utc).isoformat()
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
    graph: AgentGraph,
    vertex_id: str,
    error: str,
) -> GraphVertex | None:
    """Mark a vertex as FAILED."""
    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        return None

    vertex.status = VertexStatus.FAILED
    vertex.error = error
    vertex.completed_at = datetime.now(timezone.utc).isoformat()

    # Fill outgoing edge payloads with error context (unblock downstream)
    error_payload = EdgePayload(
        source_vertex_id=vertex.id,
        source_vertex_title=vertex.title,
        summary=f"FAILED: {error[:200]}",
        context=f"Vertex '{vertex.title}' failed: {error}",
    )
    for edge in get_outgoing_edges(graph, vertex_id):
        edge.payload = error_payload

    # Update readiness of downstream vertices
    for edge in get_outgoing_edges(graph, vertex_id):
        _update_vertex_readiness(graph, edge.target_id)

    _check_graph_completion(graph)
    return vertex


def skip_vertex(graph: AgentGraph, vertex_id: str) -> GraphVertex | None:
    """Mark a vertex as SKIPPED (e.g. conditional branch not taken)."""
    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        return None

    vertex.status = VertexStatus.SKIPPED
    vertex.completed_at = datetime.now(timezone.utc).isoformat()
    return vertex


# ---------------------------------------------------------------------------
# Topological sort
# ---------------------------------------------------------------------------


def topological_order(graph: AgentGraph) -> list[str]:
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


def has_cycle(graph: AgentGraph) -> bool:
    """Check if the graph contains a cycle."""
    try:
        topological_order(graph)
        return False
    except ValueError:
        return True


# ---------------------------------------------------------------------------
# Graph statistics
# ---------------------------------------------------------------------------


def get_stats(graph: AgentGraph) -> dict:
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


def get_final_result(graph: AgentGraph) -> str:
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

    # Collect results from terminal vertices (including failed)
    results = []
    for vid in terminal_ids:
        vertex = graph.vertices[vid]
        if vertex.status == VertexStatus.COMPLETED and vertex.result:
            results.append(vertex.result)
        elif vertex.status == VertexStatus.FAILED and vertex.error:
            results.append(f"FAILED ({vertex.title}): {vertex.error}")

    # If only one terminal, return its result
    if len(results) == 1:
        return results[0]

    # Multiple terminals — concatenate with headers
    parts = []
    for vid in terminal_ids:
        vertex = graph.vertices[vid]
        if vertex.status == VertexStatus.COMPLETED and vertex.result:
            parts.append(f"## {vertex.title}\n{vertex.result}")
        elif vertex.status == VertexStatus.FAILED and vertex.error:
            parts.append(f"## {vertex.title} (FAILED)\n{vertex.error}")
    return "\n\n".join(parts)


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _update_vertex_readiness(graph: AgentGraph, vertex_id: str) -> None:
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


def _propagate_failure(graph: AgentGraph, failed_vertex_id: str) -> None:
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


def _is_graph_complete(graph: AgentGraph) -> bool:
    """Check if all vertices are in a terminal state."""
    terminal = {VertexStatus.COMPLETED, VertexStatus.FAILED, VertexStatus.SKIPPED}
    return all(v.status in terminal for v in graph.vertices.values())


def _check_graph_completion(graph: AgentGraph) -> None:
    """Update graph status if all vertices are done.

    Master maps are never "completed" — they grow with each interaction.
    """
    if graph.graph_type == GraphType.MEMORY_MAP:
        return  # Master map never completes
    if _is_graph_complete(graph):
        has_failures = any(
            v.status == VertexStatus.FAILED for v in graph.vertices.values()
        )
        graph.status = GraphStatus.FAILED if has_failures else GraphStatus.COMPLETED
        graph.completed_at = datetime.now(timezone.utc).isoformat()


# ---------------------------------------------------------------------------
# Paměťová mapa (Memory Map) operations
# ---------------------------------------------------------------------------


def create_memory_map(client_id: str = "") -> AgentGraph:
    """Create a new Paměťová mapa (global singleton).

    The memory map is a persistent graph that holds all chat interactions
    and references to Myšlenkové mapy (task sub-graphs). It never completes.
    """
    graph_id = f"master-{uuid.uuid4().hex[:8]}"
    root_id = f"v-master-root-{uuid.uuid4().hex[:8]}"

    root = GraphVertex(
        id=root_id,
        title="Paměťová mapa",
        description="Globální paměťový kontext — všechny interakce a odkazy na úlohy",
        vertex_type=VertexType.ROOT,
        status=VertexStatus.COMPLETED,  # Root is always done
        depth=0,
    )

    return AgentGraph(
        id=graph_id,
        task_id="master",
        client_id=client_id,
        graph_type=GraphType.MEMORY_MAP,
        root_vertex_id=root_id,
        vertices={root_id: root},
        status=GraphStatus.EXECUTING,  # Memory map is always "executing"
        created_at=datetime.now(timezone.utc).isoformat(),
    )


def ensure_hierarchy(
    graph: AgentGraph,
    client_id: str = "",
    client_name: str = "",
    project_id: str | None = None,
    project_name: str = "",
) -> str | None:
    """Find or create CLIENT/PROJECT vertices, return parent vertex ID.

    Returns the most specific parent:
    - project vertex ID if project_id is set
    - client vertex ID if only client_id
    - None if neither (orphan at root level)
    """
    if not client_id:
        return None

    # Find or create CLIENT vertex (keyed by input_request == client_id)
    client_vertex: GraphVertex | None = None
    for v in graph.vertices.values():
        if v.vertex_type == VertexType.CLIENT and v.input_request == client_id:
            client_vertex = v
            # Update name if we now have it and didn't before
            if client_name and v.title.startswith("Client:"):
                v.title = client_name
            break

    if not client_vertex:
        client_vertex = GraphVertex(
            id=f"v-client-{uuid.uuid4().hex[:8]}",
            title=client_name or f"Client: {client_id[:12]}",
            description=f"Client {client_id}",
            vertex_type=VertexType.CLIENT,
            status=VertexStatus.COMPLETED,
            input_request=client_id,
            parent_id=graph.root_vertex_id,
            depth=1,
        )
        graph.vertices[client_vertex.id] = client_vertex

    if not project_id:
        return client_vertex.id

    # Find or create PROJECT vertex (keyed by input_request == project_id)
    project_vertex: GraphVertex | None = None
    for v in graph.vertices.values():
        if v.vertex_type == VertexType.PROJECT and v.input_request == project_id:
            project_vertex = v
            if project_name and v.title.startswith("Project:"):
                v.title = project_name
            break

    if not project_vertex:
        project_vertex = GraphVertex(
            id=f"v-project-{uuid.uuid4().hex[:8]}",
            title=project_name or f"Project: {project_id[:12]}",
            description=f"Project {project_id}",
            vertex_type=VertexType.PROJECT,
            status=VertexStatus.COMPLETED,
            input_request=project_id,
            parent_id=client_vertex.id,
            depth=2,
        )
        graph.vertices[project_vertex.id] = project_vertex

    return project_vertex.id


def add_request_vertex(
    graph: AgentGraph,
    message: str,
    response: str,
    response_summary: str = "",
    client_id: str = "",
    client_name: str = "",
    project_id: str | None = None,
    project_name: str = "",
    status: VertexStatus = VertexStatus.COMPLETED,
) -> GraphVertex:
    """Add a REQUEST vertex to the master map.

    Records a chat message→response pair. Nested under client/project if known.
    Status is determined by the caller:
    - COMPLETED: simple Q&A, no pending work
    - RUNNING: background tasks dispatched, ongoing work
    - FAILED: tool calls returned errors
    """
    parent_id = ensure_hierarchy(graph, client_id, client_name, project_id, project_name)
    depth = 1
    if parent_id and parent_id in graph.vertices:
        depth = graph.vertices[parent_id].depth + 1

    now = datetime.now(timezone.utc).isoformat()
    vertex = GraphVertex(
        id=f"v-chat-{uuid.uuid4().hex[:12]}",
        title=message[:80] if message else "Chat",
        description=message,
        vertex_type=VertexType.REQUEST,
        status=status,
        result=response,
        result_summary=response_summary or response[:200],
        started_at=now,
        completed_at=now if status == VertexStatus.COMPLETED else "",
        parent_id=parent_id,
        depth=depth,
    )
    graph.vertices[vertex.id] = vertex
    return vertex


def add_task_ref_vertex(
    graph: AgentGraph,
    task_id: str,
    sub_graph_id: str,
    title: str,
    completed: bool = False,
    failed: bool = False,
    result_summary: str = "",
    parent_vertex_id: str | None = None,
    client_id: str = "",
    client_name: str = "",
    project_id: str | None = None,
    project_name: str = "",
) -> GraphVertex:
    """Add or update a TASK_REF vertex linking to a task sub-graph.

    Upserts: if a TASK_REF with matching task_id already exists, updates it
    in place. Otherwise creates a new vertex.

    Parent resolution order:
    1. explicit parent_vertex_id (sub-task nesting)
    2. client/project hierarchy (ensure_hierarchy)
    3. root level (depth=1)
    """
    now = datetime.now(timezone.utc).isoformat()
    if failed:
        status = VertexStatus.FAILED
    elif completed:
        status = VertexStatus.COMPLETED
    else:
        status = VertexStatus.RUNNING

    # Resolve parent: explicit parent > client/project hierarchy
    effective_parent = parent_vertex_id
    if not effective_parent:
        effective_parent = ensure_hierarchy(graph, client_id, client_name, project_id, project_name)

    # Calculate depth from parent
    depth = 1
    if effective_parent and effective_parent in graph.vertices:
        depth = graph.vertices[effective_parent].depth + 1

    # Find existing TASK_REF for this task_id (stored in input_request)
    existing: GraphVertex | None = None
    for v in graph.vertices.values():
        if v.vertex_type == VertexType.TASK_REF and v.input_request == task_id:
            existing = v
            break

    if existing:
        # Update existing vertex
        existing.status = status
        existing.title = title
        if sub_graph_id:
            existing.local_context = sub_graph_id
        if result_summary:
            existing.result_summary = result_summary
        existing.error = result_summary if failed else None
        if completed or failed:
            existing.completed_at = now
        # Update parent if newly provided
        if effective_parent and not existing.parent_id:
            existing.parent_id = effective_parent
            existing.depth = depth
        return existing

    # Create new vertex
    vertex = GraphVertex(
        id=f"v-taskref-{uuid.uuid4().hex[:12]}",
        title=title,
        description=title,
        vertex_type=VertexType.TASK_REF,
        status=status,
        input_request=task_id,
        local_context=sub_graph_id,
        result_summary=result_summary,
        error=result_summary if failed else None,
        parent_id=effective_parent,
        started_at=now,
        completed_at=now if (completed or failed) else None,
        depth=depth,
    )
    graph.vertices[vertex.id] = vertex
    return vertex


def add_incoming_vertex(
    graph: AgentGraph,
    task_id: str,
    title: str,
    prepared_context: str = "",
    client_id: str = "",
    client_name: str = "",
    project_id: str | None = None,
    project_name: str = "",
    urgency: str = "normal",
) -> GraphVertex:
    """Add an INCOMING vertex — qualified item waiting for user decision.

    Created by the qualifier after processing an indexed item.
    Status = READY (waiting for user to decide action).
    """
    parent_id = ensure_hierarchy(graph, client_id, client_name, project_id, project_name)
    depth = 1
    if parent_id and parent_id in graph.vertices:
        depth = graph.vertices[parent_id].depth + 1

    vertex = GraphVertex(
        id=f"v-incoming-{uuid.uuid4().hex[:12]}",
        title=title[:80],
        description=prepared_context[:500] if prepared_context else title,
        vertex_type=VertexType.INCOMING,
        status=VertexStatus.READY,
        input_request=task_id,
        local_context=prepared_context,
        parent_id=parent_id,
        depth=depth,
        started_at=datetime.now(timezone.utc).isoformat(),
    )
    graph.vertices[vertex.id] = vertex
    logger.info("Added INCOMING vertex %s for task %s (urgency=%s)", vertex.id, task_id, urgency)
    return vertex


def create_ask_user_vertex(
    graph: AgentGraph,
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
        started_at=datetime.now(timezone.utc).isoformat(),
    )
    graph.vertices[vertex.id] = vertex
    return vertex


def block_vertex(graph: AgentGraph, vertex_id: str, reason: str = "") -> GraphVertex | None:
    """Block a running vertex (e.g. waiting for user input)."""
    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        return None
    vertex.status = VertexStatus.BLOCKED
    if reason:
        vertex.error = reason
    return vertex


def resume_vertex(
    graph: AgentGraph,
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


def find_blocked_vertices(graph: AgentGraph) -> list[GraphVertex]:
    """Find all BLOCKED (ASK_USER) vertices in the graph."""
    return [
        v for v in graph.vertices.values()
        if v.status == VertexStatus.BLOCKED
    ]


def memory_map_summary(graph: AgentGraph, max_tokens: int = 2000) -> str:
    """Generate a compact summary of the master map for LLM context injection.

    Priority order:
    1. BLOCKED vertices (user needs to act)
    2. Active (RUNNING) task refs
    3. Recent chat exchanges (newest first, title only)
    4. Recent completed tasks (last 5, title + brief summary)

    Skips scheduled/idle tasks — only user-initiated work matters for context.
    """
    parts: list[str] = []
    token_count = 0

    def _budget_ok() -> bool:
        return token_count < max_tokens

    def _add(line: str) -> bool:
        nonlocal token_count
        t = estimate_tokens(line)
        if token_count + t > max_tokens:
            return False
        token_count += t
        parts.append(line)
        return True

    # 1. BLOCKED vertices (highest priority — user needs to act)
    blocked = find_blocked_vertices(graph)
    if blocked:
        _add("## Waiting for your answer:")
        for v in blocked:
            if not _add(f"- [{v.id}] {v.description[:200]}"):
                break

    # 2. Active task refs (RUNNING only, skip scheduled/idle noise)
    active_refs = sorted(
        [v for v in graph.vertices.values()
         if v.vertex_type == VertexType.TASK_REF
         and v.status in (VertexStatus.RUNNING, VertexStatus.BLOCKED)],
        key=lambda v: v.started_at or "0",
        reverse=True,
    )
    if active_refs and _budget_ok():
        _add("\n## Active tasks:")
        for v in active_refs[:10]:  # Max 10 active
            if not _add(f"- {v.title[:100]} ({v.status.value})"):
                break
        if len(active_refs) > 10:
            _add(f"- ... +{len(active_refs) - 10} more active tasks")

    # 3. Recent chat exchanges (newest first, title only — brief)
    chats = sorted(
        [v for v in graph.vertices.values()
         if v.vertex_type == VertexType.REQUEST],
        key=lambda v: v.completed_at or "0",
        reverse=True,
    )
    if chats and _budget_ok():
        _add("\n## Recent conversation:")
        for v in chats[:15]:  # Last 15 exchanges
            title = v.title[:120] if v.title else "?"
            if not _add(f"- {title}"):
                _add(f"- ... ({len(chats)} total exchanges)")
                break

    # 4. Recent completed tasks (last 5 with summary snippet)
    done_refs = sorted(
        [v for v in graph.vertices.values()
         if v.vertex_type == VertexType.TASK_REF
         and v.status == VertexStatus.COMPLETED],
        key=lambda v: v.completed_at or "0",
        reverse=True,
    )
    if done_refs and _budget_ok():
        _add("\n## Recently completed:")
        for v in done_refs[:5]:
            summary = f": {v.result_summary[:100]}" if v.result_summary else ""
            if not _add(f"- {v.title[:80]}{summary}"):
                break

    return "\n".join(parts) if parts else ""
