"""Thinking Map — graph-based planning in chat.

Creates and manages AgentGraph instances during chat conversation.
The LLM calls create/add/update/remove tools, and when ready,
dispatch_map converts it into a background task for execution.

Replaces the old DraftPlan (work_plan_draft.py) system.
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone

from app.agent.models import (
    EdgeType,
    GraphEdge,
    GraphStatus,
    GraphVertex,
    AgentGraph,
    VertexStatus,
    VertexType,
)
from app.agent.persistence import agent_store

logger = logging.getLogger(__name__)

# Session → graph_id mapping (in-memory, per orchestrator pod)
_active_maps: dict[str, str] = {}

# Vertex type mapping from tool args
_VERTEX_TYPE_MAP: dict[str, VertexType] = {
    "investigator": VertexType.INVESTIGATOR,
    "executor": VertexType.EXECUTOR,
    "validator": VertexType.VALIDATOR,
    "reviewer": VertexType.REVIEWER,
    "planner": VertexType.PLANNER,
    "setup": VertexType.SETUP,
    "synthesis": VertexType.SYNTHESIS,
    "gate": VertexType.GATE,
}


async def create_map(
    title: str,
    session_id: str,
    client_id: str | None = None,
    project_id: str | None = None,
) -> AgentGraph:
    """Create a new thinking map with a root vertex."""
    graph_id = str(uuid.uuid4())
    root_id = "root"
    now = datetime.now(timezone.utc).isoformat()

    effective_client_id = client_id or ""
    if not effective_client_id:
        logger.warning("create_map: no client_id for thinking map '%s'", title)

    root_vertex = GraphVertex(
        id=root_id,
        title=title,
        description=title,
        vertex_type=VertexType.ROOT,
        status=VertexStatus.READY,
        depth=0,
        client_id=effective_client_id,
        project_id=project_id or "",
    )

    graph = AgentGraph(
        id=graph_id,
        task_id=graph_id,  # Use graph_id as task_id until dispatch
        client_id=effective_client_id,
        project_id=project_id,
        root_vertex_id=root_id,
        vertices={root_id: root_vertex},
        edges=[],
        status=GraphStatus.BUILDING,
        created_at=now,
    )

    await agent_store.save(graph)
    agent_store.cache_subgraph(graph)
    _active_maps[session_id] = graph_id
    logger.info("Created thinking map: %s (%s) for session %s", title, graph_id, session_id)
    return graph


async def add_vertex(
    session_id: str,
    title: str,
    description: str,
    vertex_type: str = "executor",
    depends_on: list[str] | None = None,
) -> tuple[AgentGraph, GraphVertex]:
    """Add a vertex to the active thinking map.

    depends_on: list of vertex titles (matched by title, not ID).
    Returns updated graph and the new vertex.
    """
    graph = await get_active_map(session_id)
    if not graph:
        raise ValueError("No active thinking map. Call create_thinking_map first.")

    vertex_id = str(uuid.uuid4())[:8]
    vtype = _VERTEX_TYPE_MAP.get(vertex_type, VertexType.EXECUTOR)

    # Calculate depth from dependencies
    dep_ids: list[str] = []
    max_depth = 0
    if depends_on:
        for dep_title in depends_on:
            for vid, v in graph.vertices.items():
                if v.title.lower() == dep_title.lower():
                    dep_ids.append(vid)
                    max_depth = max(max_depth, v.depth)
                    break

    vertex = GraphVertex(
        id=vertex_id,
        title=title,
        description=description,
        vertex_type=vtype,
        status=VertexStatus.PENDING,
        parent_id="root",
        depth=max_depth + 1 if dep_ids else 1,
        client_id=graph.client_id,
    )

    graph.vertices[vertex_id] = vertex

    # Create edges from dependencies
    if dep_ids:
        for dep_id in dep_ids:
            edge = GraphEdge(
                id=f"e-{dep_id}-{vertex_id}",
                source_id=dep_id,
                target_id=vertex_id,
                edge_type=EdgeType.DEPENDENCY,
            )
            graph.edges.append(edge)
    else:
        # Connect to root
        edge = GraphEdge(
            id=f"e-root-{vertex_id}",
            source_id="root",
            target_id=vertex_id,
            edge_type=EdgeType.DECOMPOSITION,
        )
        graph.edges.append(edge)

    await agent_store.save(graph)
    agent_store.cache_subgraph(graph)
    logger.info("Added vertex '%s' (%s) to map %s", title, vertex_id, graph.id)
    return graph, vertex


async def update_vertex(
    session_id: str,
    vertex_id: str,
    title: str | None = None,
    description: str | None = None,
    vertex_type: str | None = None,
) -> tuple[AgentGraph, GraphVertex]:
    """Update an existing vertex in the active map."""
    graph = await get_active_map(session_id)
    if not graph:
        raise ValueError("No active thinking map.")

    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        raise ValueError(f"Vertex '{vertex_id}' not found.")

    if title:
        vertex.title = title
    if description:
        vertex.description = description
    if vertex_type:
        vertex.vertex_type = _VERTEX_TYPE_MAP.get(vertex_type, vertex.vertex_type)

    await agent_store.save(graph)
    agent_store.cache_subgraph(graph)
    logger.info("Updated vertex '%s' in map %s", vertex_id, graph.id)
    return graph, vertex


async def remove_vertex(
    session_id: str,
    vertex_id: str,
) -> AgentGraph:
    """Remove a vertex and its edges from the active map."""
    graph = await get_active_map(session_id)
    if not graph:
        raise ValueError("No active thinking map.")

    if vertex_id == "root":
        raise ValueError("Cannot remove root vertex.")

    if vertex_id not in graph.vertices:
        raise ValueError(f"Vertex '{vertex_id}' not found.")

    del graph.vertices[vertex_id]
    graph.edges = [e for e in graph.edges if e.source_id != vertex_id and e.target_id != vertex_id]

    await agent_store.save(graph)
    agent_store.cache_subgraph(graph)
    logger.info("Removed vertex '%s' from map %s", vertex_id, graph.id)
    return graph


async def dispatch_map(
    session_id: str,
    kotlin_client,
    client_id: str | None = None,
    project_id: str | None = None,
) -> str:
    """Finalize the thinking map and dispatch as a background task.

    Returns the created task ID.
    """
    graph = await get_active_map(session_id)
    if not graph:
        raise ValueError("No active thinking map to dispatch.")

    # Update graph status
    graph.status = GraphStatus.READY
    effective_client_id = client_id or graph.client_id
    effective_project_id = project_id or graph.project_id
    graph.client_id = effective_client_id
    graph.project_id = effective_project_id

    # Get root title for task
    root = graph.vertices.get(graph.root_vertex_id)
    title = root.title if root else "Thinking Map"
    description = f"Myšlenková mapa: {title} ({len(graph.vertices)} kroků)"

    # Create background task via Kotlin
    task_id = await kotlin_client.create_background_task(
        title=title,
        description=description,
        client_id=effective_client_id,
        project_id=effective_project_id,
    )

    # Re-key the graph to the real task_id
    graph.task_id = task_id
    await agent_store.save(graph)
    agent_store.cache_subgraph(graph)

    # Clear session mapping
    _active_maps.pop(session_id, None)

    logger.info("Dispatched thinking map %s as task %s", graph.id, task_id)
    return task_id


async def get_active_map(session_id: str) -> AgentGraph | None:
    """Get the active thinking map for a session."""
    graph_id = _active_maps.get(session_id)
    if not graph_id:
        return None
    graph = await agent_store.load(graph_id)
    if not graph:
        _active_maps.pop(session_id, None)
        return None
    return graph


async def run_vertex(
    session_id: str,
    vertex_id: str,
    kotlin_client,
    client_id: str | None = None,
    project_id: str | None = None,
) -> str:
    """Dispatch a single vertex as a background task while keeping the map active.

    The vertex is marked as READY (executing), and a background task is created
    with correlation metadata so results can flow back into the graph.
    Returns the created task ID.
    """
    graph = await get_active_map(session_id)
    if not graph:
        raise ValueError("No active thinking map.")

    vertex = graph.vertices.get(vertex_id)
    if not vertex:
        raise ValueError(f"Vertex '{vertex_id}' not found.")

    # Mark vertex as executing
    vertex.status = VertexStatus.READY
    effective_client_id = client_id or graph.client_id
    effective_project_id = project_id or graph.project_id

    # Create background task with vertex correlation
    result = await kotlin_client.create_background_task(
        title=f"[Mapa] {vertex.title}",
        description=vertex.description,
        client_id=effective_client_id,
        project_id=effective_project_id,
    )
    # Result is a dict {"taskId": "...", "title": "..."} or error string
    if isinstance(result, dict):
        task_id = result.get("taskId", str(result))
    else:
        task_id = str(result)

    # Persist correlation: task_id → (graph_id, vertex_id, session_id)
    await agent_store.save_vertex_correlation(task_id, graph.id, vertex_id, session_id)

    await agent_store.save(graph)
    logger.info(
        "Dispatched vertex '%s' (%s) from map %s as task %s",
        vertex.title, vertex_id, graph.id, task_id,
    )
    return task_id


async def handle_vertex_result(
    task_id: str,
    result: str,
) -> tuple[AgentGraph, str] | None:
    """Handle a background task result that corresponds to a dispatched vertex.

    Returns (updated graph, session_id) if this was a vertex task, None otherwise.
    """
    correlation = await agent_store.pop_vertex_correlation(task_id)
    if not correlation:
        return None

    graph_id, vertex_id, session_id = correlation
    graph = await agent_store.load_by_graph_id(graph_id)
    if not graph:
        logger.warning("Graph %s not found for vertex result (task %s)", graph_id, task_id)
        return None

    vertex = graph.vertices.get(vertex_id)
    if vertex:
        vertex.status = VertexStatus.COMPLETED
        vertex.result = result or ""
        vertex.result_summary = result or ""
        await agent_store.save(graph)
        logger.info("Updated vertex '%s' with background result (task %s)", vertex_id, task_id)

    return graph, session_id


def get_active_map_id(session_id: str) -> str | None:
    """Get the active map ID without loading from DB."""
    return _active_maps.get(session_id)
