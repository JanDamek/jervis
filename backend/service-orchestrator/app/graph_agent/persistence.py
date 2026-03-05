"""MongoDB persistence for TaskGraph + in-memory master map cache.

Stores the complete graph (vertices, edges) as a single document.
Supports:
- save/load full graph
- atomic vertex status updates (without rewriting entire document)
- edge payload updates
- TTL-based auto-cleanup (30 days)
- In-memory master map singleton (DB = restart recovery only)
- RAM cache for active task sub-graphs
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorCollection

from app.config import settings
from app.graph_agent.models import (
    EdgePayload,
    GraphEdge,
    GraphStatus,
    GraphType,
    GraphVertex,
    TaskGraph,
    VertexStatus,
)

logger = logging.getLogger(__name__)

_COLLECTION_NAME = "task_graphs"
_TTL_DAYS = 30
_FLUSH_INTERVAL_S = 30  # Periodic DB flush interval


class TaskGraphStore:
    """MongoDB-backed persistence for task graphs with RAM cache.

    Master map is held in RAM as a singleton. Task sub-graphs are cached
    in RAM while active. DB is used for restart recovery only.
    """

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._collection: AsyncIOMotorCollection | None = None

        # RAM cache
        self._master_graph: TaskGraph | None = None
        self._subgraphs: dict[str, TaskGraph] = {}  # task_id → TaskGraph
        self._dirty: set[str] = set()  # task_ids that need DB flush
        self._flush_task: asyncio.Task | None = None

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
            "TaskGraphStore initialized (collection=%s, ttl=%dd)",
            _COLLECTION_NAME, _TTL_DAYS,
        )

        # Start periodic flush
        if self._flush_task is None:
            self._flush_task = asyncio.create_task(self._periodic_flush())

    async def _ensure_collection(self) -> AsyncIOMotorCollection:
        if self._collection is None:
            await self.init()
        assert self._collection is not None
        return self._collection

    # --- Full graph operations ---

    async def save(self, graph: TaskGraph) -> None:
        """Save or update the entire graph document."""
        coll = await self._ensure_collection()
        doc = graph.model_dump()
        doc["updated_at"] = datetime.now(timezone.utc)
        await coll.replace_one(
            {"task_id": graph.task_id},
            doc,
            upsert=True,
        )

    async def load(self, task_id: str) -> TaskGraph | None:
        """Load a graph by task_id. Returns None if not found."""
        coll = await self._ensure_collection()
        doc = await coll.find_one({"task_id": task_id})
        if not doc:
            return None
        doc.pop("_id", None)
        doc.pop("updated_at", None)
        return TaskGraph(**doc)

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

    async def get_or_create_master_graph(self, client_id: str = "") -> TaskGraph:
        """Get or create the global master map (one per orchestrator instance).

        RAM-first: returns cached graph if available. Falls back to DB on
        cold start. Creates new master map if none exists.
        """
        if self._master_graph is not None:
            return self._master_graph

        # Try loading from DB
        coll = await self._ensure_collection()
        doc = await coll.find_one({"graph_type": GraphType.MASTER.value})
        if doc:
            doc.pop("_id", None)
            doc.pop("updated_at", None)
            self._master_graph = TaskGraph(**doc)
            logger.info("Master map loaded from DB (id=%s, vertices=%d)",
                        self._master_graph.id, len(self._master_graph.vertices))
            return self._master_graph

        # Create new master map
        from app.graph_agent.graph import create_master_graph
        self._master_graph = create_master_graph(client_id)
        self._dirty.add(self._master_graph.task_id)
        logger.info("New master map created (id=%s)", self._master_graph.id)
        return self._master_graph

    def get_master_graph_cached(self) -> TaskGraph | None:
        """Get master map from RAM only (no DB fallback). For sync callers."""
        return self._master_graph

    # --- Sub-graph RAM cache ---

    def cache_subgraph(self, graph: TaskGraph) -> None:
        """Put a task sub-graph into the RAM cache."""
        self._subgraphs[graph.task_id] = graph

    def get_cached_subgraph(self, task_id: str) -> TaskGraph | None:
        """Get a sub-graph from RAM cache."""
        return self._subgraphs.get(task_id)

    def remove_cached_subgraph(self, task_id: str) -> None:
        """Remove a completed sub-graph from RAM cache."""
        self._subgraphs.pop(task_id, None)

    # --- Link master ↔ sub-graph ---

    async def link_task_subgraph(
        self,
        task_id: str,
        sub_graph_id: str,
        title: str,
    ) -> None:
        """Link a task sub-graph to the master map via a TASK_REF vertex."""
        master = await self.get_or_create_master_graph()
        from app.graph_agent.graph import add_task_ref_vertex
        add_task_ref_vertex(master, task_id, sub_graph_id, title)
        self._dirty.add(master.task_id)

    # --- ASK_USER helpers ---

    async def find_ask_user_vertices(self) -> list[tuple[str, GraphVertex]]:
        """Find all BLOCKED ASK_USER vertices across master + cached sub-graphs.

        Returns list of (graph_task_id, vertex) tuples.
        """
        results: list[tuple[str, GraphVertex]] = []

        # Check master map
        if self._master_graph:
            for v in self._master_graph.vertices.values():
                if v.status == VertexStatus.BLOCKED:
                    results.append((self._master_graph.task_id, v))

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
        from app.graph_agent.graph import resume_vertex

        graph: TaskGraph | None = None
        if self._master_graph and self._master_graph.task_id == task_id:
            graph = self._master_graph
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
            graph: TaskGraph | None = None
            if self._master_graph and self._master_graph.task_id == task_id:
                graph = self._master_graph
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
task_graph_store = TaskGraphStore()
