"""MongoDB persistence for AgentGraph + in-memory Paměťová mapa cache.

Stores the complete graph (vertices, edges) as a single document.
Supports:
- save/load full graph
- atomic vertex status updates (without rewriting entire document)
- edge payload updates
- TTL-based auto-cleanup (30 days)
- In-memory Paměťová mapa singleton (DB = restart recovery only)
- RAM cache for active Myšlenkové mapy
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorCollection

from app.config import settings
from app.agent.models import (
    EdgePayload,
    GraphEdge,
    GraphStatus,
    GraphType,
    GraphVertex,
    AgentGraph,
    VertexStatus,
    VertexType,
)

logger = logging.getLogger(__name__)

_COLLECTION_NAME = "task_graphs"
_TTL_DAYS = 30
_FLUSH_INTERVAL_S = 30  # Periodic DB flush interval
_CLEANUP_INTERVAL_S = 600  # Master map cleanup every 10 min
_KEEP_CHAT_VERTICES = 5  # Keep last N chat exchanges
_KEEP_TASK_VERTICES = 5  # Keep last N completed/failed task refs


class AgentStore:
    """MongoDB-backed persistence for task graphs with RAM cache.

    Master map is held in RAM as a singleton. Task sub-graphs are cached
    in RAM while active. DB is used for restart recovery only.
    """

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._collection: AsyncIOMotorCollection | None = None

        # RAM cache
        self._memory_map: AgentGraph | None = None
        self._subgraphs: dict[str, AgentGraph] = {}  # task_id → AgentGraph
        self._dirty: set[str] = set()  # task_ids that need DB flush
        self._flush_task: asyncio.Task | None = None
        self._cleanup_task: asyncio.Task | None = None
        self._pending_archive: list[dict] = []

        # Task parent tracking: child_task_id → parent_task_id
        # Used to nest sub-tasks under their parent TASK_REF in master map
        self._task_parent_map: dict[str, str] = {}

    async def init(self) -> None:
        """Initialize MongoDB connection and create indexes."""
        self._client = AsyncIOMotorClient(settings.mongodb_url)
        db = self._client.get_database("jervis")
        self._collection = db[_COLLECTION_NAME]

        await self._collection.create_index(
            [("task_id", 1)], unique=True,
        )
        await self._collection.create_index(
            [("client_id", 1), ("status", 1)],
        )
        await self._collection.create_index(
            [("graph_type", 1)],
        )
        await self._collection.create_index(
            [("updated_at", 1)],
            expireAfterSeconds=_TTL_DAYS * 86400,
        )
        logger.info(
            "AgentStore initialized (collection=%s, ttl=%dd)",
            _COLLECTION_NAME, _TTL_DAYS,
        )

        # Start periodic flush + cleanup
        if self._flush_task is None:
            self._flush_task = asyncio.create_task(self._periodic_flush())
        if self._cleanup_task is None:
            self._cleanup_task = asyncio.create_task(self._periodic_cleanup())

    async def _ensure_collection(self) -> AsyncIOMotorCollection:
        if self._collection is None:
            await self.init()
        assert self._collection is not None
        return self._collection

    # --- Full graph operations ---

    async def save(self, graph: AgentGraph) -> None:
        """Save or update the entire graph document."""
        coll = await self._ensure_collection()
        doc = graph.model_dump()
        doc["updated_at"] = datetime.now(timezone.utc)
        await coll.replace_one(
            {"task_id": graph.task_id},
            doc,
            upsert=True,
        )

    async def load(self, task_id: str) -> AgentGraph | None:
        """Load a graph by task_id. Returns None if not found."""
        coll = await self._ensure_collection()
        doc = await coll.find_one({"task_id": task_id})
        if not doc:
            return None
        doc.pop("_id", None)
        doc.pop("updated_at", None)
        return AgentGraph(**doc)

    async def delete(self, task_id: str) -> bool:
        """Delete a graph by task_id. Returns True if found."""
        coll = await self._ensure_collection()
        result = await coll.delete_one({"task_id": task_id})
        return result.deleted_count > 0

    # --- Atomic vertex updates ---

    async def update_vertex_status(
        self,
        task_id: str,
        vertex_id: str,
        status: VertexStatus,
        result: str | None = None,
        result_summary: str | None = None,
        local_context: str | None = None,
        error: str | None = None,
        started_at: str | None = None,
        completed_at: str | None = None,
        token_count: int | None = None,
        llm_calls: int | None = None,
    ) -> bool:
        """Atomically update a single vertex's status and result fields.

        Uses MongoDB's dot notation to update nested fields without
        rewriting the entire document.
        """
        coll = await self._ensure_collection()

        update_fields: dict = {
            f"vertices.{vertex_id}.status": status.value,
            "updated_at": datetime.now(timezone.utc),
        }
        if result is not None:
            update_fields[f"vertices.{vertex_id}.result"] = result
        if result_summary is not None:
            update_fields[f"vertices.{vertex_id}.result_summary"] = result_summary
        if local_context is not None:
            update_fields[f"vertices.{vertex_id}.local_context"] = local_context
        if error is not None:
            update_fields[f"vertices.{vertex_id}.error"] = error
        if started_at is not None:
            update_fields[f"vertices.{vertex_id}.started_at"] = started_at
        if completed_at is not None:
            update_fields[f"vertices.{vertex_id}.completed_at"] = completed_at
        if token_count is not None:
            update_fields[f"vertices.{vertex_id}.token_count"] = token_count
        if llm_calls is not None:
            update_fields[f"vertices.{vertex_id}.llm_calls"] = llm_calls

        result_op = await coll.update_one(
            {"task_id": task_id},
            {"$set": update_fields},
        )
        return result_op.modified_count > 0

    async def update_edge_payload(
        self,
        task_id: str,
        edge_id: str,
        payload: EdgePayload,
    ) -> bool:
        """Atomically update an edge's payload by edge ID (not index)."""
        coll = await self._ensure_collection()
        result_op = await coll.update_one(
            {"task_id": task_id, "edges.id": edge_id},
            {
                "$set": {
                    "edges.$.payload": payload.model_dump(),
                    "updated_at": datetime.now(timezone.utc),
                },
            },
        )
        return result_op.modified_count > 0

    async def update_graph_status(
        self,
        task_id: str,
        status: GraphStatus,
        completed_at: str | None = None,
    ) -> bool:
        """Atomically update the graph's overall status."""
        coll = await self._ensure_collection()
        update: dict = {
            "status": status.value,
            "updated_at": datetime.now(timezone.utc),
        }
        if completed_at is not None:
            update["completed_at"] = completed_at
        result_op = await coll.update_one(
            {"task_id": task_id},
            {"$set": update},
        )
        return result_op.modified_count > 0

    # --- Master Map (RAM singleton) ---

    async def get_or_create_memory_map(self, client_id: str = "") -> AgentGraph:
        """Get or create the global master map (one per orchestrator instance).

        RAM-first: returns cached graph if available. Falls back to DB on
        cold start. Creates new master map if none exists.
        """
        if self._memory_map is not None:
            return self._memory_map

        # Try loading from DB
        coll = await self._ensure_collection()
        doc = await coll.find_one({"graph_type": GraphType.MEMORY_MAP.value})
        if doc:
            doc.pop("_id", None)
            doc.pop("updated_at", None)
            self._memory_map = AgentGraph(**doc)
            logger.info("Master map loaded from DB (id=%s, vertices=%d)",
                        self._memory_map.id, len(self._memory_map.vertices))
            # Immediate cleanup on load — archive removed vertices
            removed = self.cleanup_master_map()
            if removed > 0:
                if hasattr(self, "_pending_archive") and self._pending_archive:
                    await self._archive_vertices(self._pending_archive)
                    self._pending_archive = []
                self._dirty.add(self._memory_map.task_id)
                logger.info("Master map after cleanup: %d vertices", len(self._memory_map.vertices))
            return self._memory_map

        # Create new master map
        from app.agent.graph import create_memory_map
        self._memory_map = create_memory_map(client_id)
        self._dirty.add(self._memory_map.task_id)
        logger.info("New master map created (id=%s)", self._memory_map.id)
        return self._memory_map

    def get_memory_map_cached(self) -> AgentGraph | None:
        """Get master map from RAM only (no DB fallback). For sync callers."""
        return self._memory_map

    # --- Sub-graph RAM cache ---

    def cache_subgraph(self, graph: AgentGraph) -> None:
        """Put a task sub-graph into the RAM cache."""
        self._subgraphs[graph.task_id] = graph

    def get_cached_subgraph(self, task_id: str) -> AgentGraph | None:
        """Get a sub-graph from RAM cache."""
        return self._subgraphs.get(task_id)

    def remove_cached_subgraph(self, task_id: str) -> None:
        """Remove a completed sub-graph from RAM cache."""
        self._subgraphs.pop(task_id, None)

    # --- Task parent tracking (child → parent nesting in master map) ---

    def register_task_parent(self, child_task_id: str, parent_task_id: str) -> None:
        """Register a child task's parent for master map nesting."""
        self._task_parent_map[child_task_id] = parent_task_id
        logger.debug("Task parent registered: %s → %s", child_task_id, parent_task_id)

    def get_task_parent(self, child_task_id: str) -> str | None:
        """Get parent task_id for a child task."""
        return self._task_parent_map.get(child_task_id)

    def _find_parent_vertex_id(self, parent_task_id: str) -> str | None:
        """Find the master map vertex ID for a parent task_id."""
        if not self._memory_map:
            return None
        for v in self._memory_map.vertices.values():
            if v.vertex_type == VertexType.TASK_REF and v.input_request == parent_task_id:
                return v.id
        return None

    # --- Link master ↔ sub-graph ---

    async def link_thinking_map(
        self,
        task_id: str,
        sub_graph_id: str,
        title: str,
        completed: bool = False,
        failed: bool = False,
        result_summary: str = "",
        client_id: str = "",
        client_name: str = "",
        project_id: str | None = None,
        project_name: str = "",
    ) -> None:
        """Link a task sub-graph to the master map via a TASK_REF vertex.

        Parent resolution order:
        1. Task parent (sub-task nesting via _task_parent_map)
        2. Client/project hierarchy (auto-creates CLIENT/PROJECT vertices)
        """
        master = await self.get_or_create_memory_map()
        from app.agent.graph import add_task_ref_vertex

        # Find parent vertex for nesting (sub-task → parent task)
        parent_vertex_id: str | None = None
        parent_task_id = self.get_task_parent(task_id)
        if parent_task_id:
            parent_vertex_id = self._find_parent_vertex_id(parent_task_id)

        add_task_ref_vertex(
            master, task_id, sub_graph_id, title,
            completed=completed, failed=failed, result_summary=result_summary,
            parent_vertex_id=parent_vertex_id,
            client_id=client_id, client_name=client_name,
            project_id=project_id, project_name=project_name,
        )
        self._dirty.add(master.task_id)

    # --- ASK_USER helpers ---

    async def find_ask_user_vertices(self) -> list[tuple[str, GraphVertex]]:
        """Find all BLOCKED ASK_USER vertices across master + cached sub-graphs.

        Returns list of (graph_task_id, vertex) tuples.
        """
        results: list[tuple[str, GraphVertex]] = []

        # Check master map
        if self._memory_map:
            for v in self._memory_map.vertices.values():
                if v.status == VertexStatus.BLOCKED:
                    results.append((self._memory_map.task_id, v))

        # Check cached sub-graphs
        for task_id, graph in self._subgraphs.items():
            for v in graph.vertices.values():
                if v.status == VertexStatus.BLOCKED:
                    results.append((task_id, v))

        return results

    async def resume_blocked_vertex(
        self,
        task_id: str,
        vertex_id: str,
        answer: str,
    ) -> bool:
        """Resume a BLOCKED vertex with user's answer.

        Looks up graph in RAM cache (master or sub-graph), resumes vertex,
        marks graph as dirty for DB flush.
        """
        from app.agent.graph import resume_vertex

        graph: AgentGraph | None = None
        if self._memory_map and self._memory_map.task_id == task_id:
            graph = self._memory_map
        elif task_id in self._subgraphs:
            graph = self._subgraphs[task_id]
        else:
            # Try DB
            graph = await self.load(task_id)
            if graph:
                self._subgraphs[task_id] = graph

        if not graph:
            logger.warning("Cannot resume vertex — graph not found (task_id=%s)", task_id)
            return False

        vertex = resume_vertex(graph, vertex_id, answer)
        if vertex:
            self._dirty.add(task_id)
            return True
        return False

    # --- Master map cleanup ---

    _ACTIVE_STATUSES = {
        VertexStatus.PENDING, VertexStatus.READY,
        VertexStatus.RUNNING, VertexStatus.BLOCKED,
    }

    def cleanup_master_map(self) -> int:
        """Remove old completed/failed vertices from master map.

        Keeps:
        - All active vertices (PENDING, READY, RUNNING, BLOCKED)
        - Last N REQUEST vertices (for summary context)
        - Last N completed/failed TASK_REF vertices (for summary context)
        - Root vertex
        - All edges referencing kept vertices

        Removed vertices are archived to `master_map_archive` collection
        via _archive_vertices() (called separately, async).

        Returns number of removed vertices.
        """
        if not self._memory_map:
            return 0

        graph = self._memory_map
        keep_ids: set[str] = set()

        # Always keep active (non-terminal) vertices
        for vid, v in graph.vertices.items():
            if v.status in self._ACTIVE_STATUSES:
                keep_ids.add(vid)

        # Keep last N chat exchanges (sorted by completed_at desc)
        chats = sorted(
            [(vid, v) for vid, v in graph.vertices.items()
             if v.vertex_type == VertexType.REQUEST],
            key=lambda x: x[1].completed_at or "0",
            reverse=True,
        )
        for vid, _ in chats[:_KEEP_CHAT_VERTICES]:
            keep_ids.add(vid)

        # Keep last N task refs (sorted by completed_at desc)
        task_refs = sorted(
            [(vid, v) for vid, v in graph.vertices.items()
             if v.vertex_type == VertexType.TASK_REF],
            key=lambda x: x[1].completed_at or "0",
            reverse=True,
        )
        for vid, _ in task_refs[:_KEEP_TASK_VERTICES]:
            keep_ids.add(vid)

        # Always keep root, CLIENT, and PROJECT vertices (structural hierarchy)
        if graph.root_vertex_id:
            keep_ids.add(graph.root_vertex_id)
        for vid, v in graph.vertices.items():
            if v.vertex_type in (VertexType.CLIENT, VertexType.PROJECT):
                keep_ids.add(vid)

        # Identify vertices to remove
        to_remove = set(graph.vertices.keys()) - keep_ids
        if not to_remove:
            return 0

        # Stash removed vertices for async archival
        self._pending_archive = [
            graph.vertices[vid].model_dump() for vid in to_remove
        ]

        for vid in to_remove:
            del graph.vertices[vid]

        # Remove edges referencing removed vertices
        graph.edges = [
            e for e in graph.edges
            if e.source_id in keep_ids and e.target_id in keep_ids
        ]

        self._dirty.add(graph.task_id)
        logger.info(
            "Master map cleanup: removed %d vertices, kept %d (active=%d, chats=%d, tasks=%d)",
            len(to_remove), len(keep_ids),
            sum(1 for v in graph.vertices.values() if v.status in self._ACTIVE_STATUSES),
            min(len(chats), _KEEP_CHAT_VERTICES),
            min(len(task_refs), _KEEP_TASK_VERTICES),
        )
        return len(to_remove)

    async def _archive_vertices(self, vertices: list[dict]) -> None:
        """Archive removed vertices to a separate MongoDB collection.

        Stores as a batch document with vertex summaries. Retrievable for
        future context if needed.
        """
        if not vertices:
            return
        try:
            coll = await self._ensure_collection()
            db = coll.database
            archive_coll = db["master_map_archive"]
            doc = {
                "archived_at": datetime.now(timezone.utc),
                "count": len(vertices),
                "vertices": [
                    {
                        "id": v.get("id"),
                        "title": v.get("title", ""),
                        "vertex_type": v.get("vertex_type", ""),
                        "status": v.get("status", ""),
                        "result_summary": (v.get("result_summary") or "")[:300],
                        "error": (v.get("error") or "")[:300],
                        "completed_at": v.get("completed_at"),
                        "input_request": v.get("input_request", ""),
                    }
                    for v in vertices
                ],
            }
            await archive_coll.insert_one(doc)
            logger.info("Archived %d vertices to master_map_archive", len(vertices))
        except Exception as e:
            logger.warning("Failed to archive vertices: %s", e)

    async def _periodic_cleanup(self) -> None:
        """Background task: cleanup master map every N seconds."""
        while True:
            try:
                await asyncio.sleep(_CLEANUP_INTERVAL_S)
                removed = self.cleanup_master_map()
                if removed > 0:
                    # Archive first, then flush
                    if hasattr(self, "_pending_archive") and self._pending_archive:
                        await self._archive_vertices(self._pending_archive)
                        self._pending_archive = []
                    await self.flush_dirty()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error("Master map cleanup error: %s", e)

    # --- Dirty flush ---

    def mark_dirty(self, task_id: str) -> None:
        """Mark a graph as needing DB flush."""
        self._dirty.add(task_id)

    async def flush_dirty(self) -> None:
        """Flush all dirty graphs to DB."""
        if not self._dirty:
            return

        dirty_ids = list(self._dirty)
        self._dirty.clear()

        for task_id in dirty_ids:
            graph: AgentGraph | None = None
            if self._memory_map and self._memory_map.task_id == task_id:
                graph = self._memory_map
            elif task_id in self._subgraphs:
                graph = self._subgraphs[task_id]

            if graph:
                try:
                    await self.save(graph)
                except Exception as e:
                    logger.error("Failed to flush graph %s: %s", task_id, e)
                    self._dirty.add(task_id)  # Re-add for next flush

    async def _periodic_flush(self) -> None:
        """Background task: flush dirty graphs to DB every N seconds."""
        while True:
            try:
                await asyncio.sleep(_FLUSH_INTERVAL_S)
                await self.flush_dirty()
            except asyncio.CancelledError:
                # Final flush on shutdown
                await self.flush_dirty()
                break
            except Exception as e:
                logger.error("Periodic flush error: %s", e)

    # --- Query helpers ---

    async def get_active_graphs(
        self,
        client_id: str | None = None,
    ) -> list[dict]:
        """Get graphs in BUILDING or EXECUTING status (summary only)."""
        coll = await self._ensure_collection()
        query: dict = {
            "status": {"$in": [GraphStatus.BUILDING.value, GraphStatus.EXECUTING.value]},
        }
        if client_id:
            query["client_id"] = client_id

        cursor = coll.find(
            query,
            {"task_id": 1, "client_id": 1, "status": 1,
             "created_at": 1, "root_vertex_id": 1, "graph_type": 1},
        )
        return [doc async for doc in cursor]

    async def list_graph_summaries(
        self,
        client_id: str | None = None,
    ) -> list[dict]:
        """Get summaries of all graphs (for UI display)."""
        coll = await self._ensure_collection()
        query: dict = {}
        if client_id:
            query["client_id"] = client_id

        cursor = coll.find(
            query,
            {"task_id": 1, "client_id": 1, "status": 1,
             "graph_type": 1, "created_at": 1, "completed_at": 1,
             "root_vertex_id": 1, "parent_graph_id": 1},
        ).sort("created_at", -1).limit(50)
        return [doc async for doc in cursor]


# Singleton
agent_store = AgentStore()
