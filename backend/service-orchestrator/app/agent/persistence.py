"""MongoDB persistence for AgentGraph + in-memory Paměťový graf cache.

Stores the complete graph (vertices, edges) as a single document.
Supports:
- save/load full graph
- atomic vertex status updates (without rewriting entire document)
- edge payload updates
- TTL-based auto-cleanup (30 days)
- In-memory Paměťový graf singleton (DB = restart recovery only)
- RAM cache for active Myšlenkové grafy
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone

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
_CLEANUP_INTERVAL_S = 600  # Memory graph cleanup every 10 min
_KEEP_CHAT_PER_CLIENT = 5  # Keep last N chat exchanges PER CLIENT
_KEEP_TASK_PER_CLIENT = 5  # Keep last N completed/failed task refs PER CLIENT
_MAX_COMPLETED_AGE_S = 86400  # 24 hours — Tier 1 retention in RAM
_MIN_KEEP_PER_CLIENT = 2  # Always keep at least N newest per category per client
_THINKING_GRAPH_TTL_S = 3600  # Thinking graphs: keep 1h in RAM after completion
_THINKING_GRAPH_HIDE_S = 600  # 10min → hidden in UI (debug visibility)
_ARCHIVE_TTL_DAYS = 7  # Tier 2: MongoDB archive retention


class AgentStore:
    """MongoDB-backed persistence for task graphs with RAM cache.

    Memory graph is held in RAM as a singleton. Task sub-graphs are cached
    in RAM while active. DB is used for restart recovery only.
    """

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._collection: AsyncIOMotorCollection | None = None
        self._vertex_tasks_coll: AsyncIOMotorCollection | None = None
        self._archive_coll: AsyncIOMotorCollection | None = None
        self._maintenance_coll: AsyncIOMotorCollection | None = None

        # RAM cache
        self._memory_graph: AgentGraph | None = None
        self._subgraphs: dict[str, AgentGraph] = {}  # task_id → AgentGraph
        self._dirty: set[str] = set()  # task_ids that need DB flush
        self._flush_task: asyncio.Task | None = None
        self._cleanup_task: asyncio.Task | None = None
        self._pending_archive: list[dict] = []

        # Task parent tracking: child_task_id → parent_task_id
        # Used to nest sub-tasks under their parent TASK_REF in memory graph
        self._task_parent_map: dict[str, str] = {}

    async def init(self) -> None:
        """Initialize MongoDB connection and create indexes."""
        self._client = AsyncIOMotorClient(settings.mongodb_url)
        db = self._client.get_database("jervis")
        self._collection = db[_COLLECTION_NAME]
        self._vertex_tasks_coll = db["vertex_task_correlations"]

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
        # Vertex task correlations — TTL 7 days (tasks don't last longer)
        await self._vertex_tasks_coll.create_index(
            [("created_at", 1)],
            expireAfterSeconds=7 * 86400,
        )

        # Tier 2: Memory graph archive — per-vertex docs with 7d TTL
        self._archive_coll = db["master_graph_archive"]
        await self._archive_coll.create_index(
            [("archived_at", 1)],
            expireAfterSeconds=_ARCHIVE_TTL_DAYS * 86400,
        )
        await self._archive_coll.create_index([("client_id", 1)])
        await self._archive_coll.create_index(
            [("title", "text"), ("result_summary", "text"), ("input_request", "text")],
        )

        # Maintenance cycles — tracks per-client maintenance progress
        self._maintenance_coll = db["maintenance_cycles"]
        await self._maintenance_coll.create_index(
            [("client_id", 1), ("task_type", 1)], unique=True,
        )
        await self._maintenance_coll.create_index(
            [("task_type", 1), ("last_run_at", 1)],
        )
        logger.info(
            "AgentStore initialized (collection=%s, ttl=%dd)",
            _COLLECTION_NAME, _TTL_DAYS,
        )

        # One-time migration: normalize old enum values in DB
        await self._migrate_old_enum_values()

        # Start periodic flush + cleanup
        if self._flush_task is None:
            self._flush_task = asyncio.create_task(self._periodic_flush())
        if self._cleanup_task is None:
            self._cleanup_task = asyncio.create_task(self._periodic_cleanup())

    async def _migrate_old_enum_values(self) -> None:
        """One-time migration: rename old GraphType and VertexType values in DB.

        Converts: master → memory_graph, task_subgraph → thinking_graph,
        memory_map → memory_graph, thinking_map → thinking_graph,
        chat_exchange → request (inside vertices).
        """
        coll = await self._ensure_collection()

        # Migrate graph_type
        for old, new in [("master", "memory_graph"), ("task_subgraph", "thinking_graph"), ("memory_map", "memory_graph"), ("thinking_map", "thinking_graph")]:
            result = await coll.update_many(
                {"graph_type": old},
                {"$set": {"graph_type": new}},
            )
            if result.modified_count > 0:
                logger.info("Migrated %d graphs: graph_type '%s' → '%s'",
                            result.modified_count, old, new)

        # Migrate vertex_type, epoch timestamps, and TASK_REF descriptions inside vertices
        # MongoDB doesn't support wildcard keys in $set, so we load + update
        from datetime import datetime, timezone
        async for doc in coll.find({"$expr": {"$gt": [{"$size": {"$objectToArray": {"$ifNull": ["$vertices", {}]}}}, 0]}}):
            changed = False
            vertices = doc.get("vertices", {})

            # Migrate graph-level epoch timestamps to ISO
            for ts_field in ("created_at", "completed_at"):
                val = doc.get(ts_field)
                if val and isinstance(val, str) and val.isdigit() and len(val) <= 12:
                    try:
                        iso = datetime.fromtimestamp(int(val), tz=timezone.utc).isoformat()
                        await coll.update_one(
                            {"_id": doc["_id"]},
                            {"$set": {ts_field: iso}},
                        )
                        changed = True
                    except Exception:
                        pass

            for vid, vdata in vertices.items():
                # vertex_type migration
                if vdata.get("vertex_type") == "chat_exchange":
                    vertices[vid]["vertex_type"] = "request"
                    changed = True

                # Epoch timestamps in vertices → ISO
                for ts_field in ("started_at", "completed_at"):
                    val = vdata.get(ts_field)
                    if val and isinstance(val, str) and val.isdigit() and len(val) <= 12:
                        try:
                            vertices[vid][ts_field] = datetime.fromtimestamp(int(val), tz=timezone.utc).isoformat()
                            changed = True
                        except Exception:
                            pass

                # TASK_REF descriptions: fix hex-only descriptions
                if vdata.get("vertex_type") == "task_ref":
                    desc = vdata.get("description", "")
                    title = vdata.get("title", "")
                    if desc.startswith("Background task ") and title and title != desc:
                        vertices[vid]["description"] = title
                        changed = True

            if changed:
                await coll.update_one(
                    {"_id": doc["_id"]},
                    {"$set": {"vertices": vertices}},
                )
                logger.info("Migrated data in graph %s", doc.get("task_id", "?"))

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

    async def load_by_graph_id(self, graph_id: str) -> AgentGraph | None:
        """Load a graph by its own id field (not task_id). Fallback for sub-graph lookup."""
        coll = await self._ensure_collection()
        doc = await coll.find_one({"id": graph_id})
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

    # --- Master Graph (RAM singleton) ---

    async def get_or_create_memory_graph(self, client_id: str = "") -> AgentGraph:
        """Get or create the global memory graph (one per orchestrator instance).

        RAM-first: returns cached graph if available. Falls back to DB on
        cold start. Creates new memory graph if none exists.
        """
        if self._memory_graph is not None:
            return self._memory_graph

        # Try loading from DB
        coll = await self._ensure_collection()
        doc = await coll.find_one({"graph_type": GraphType.MEMORY_GRAPH.value})
        if doc:
            doc.pop("_id", None)
            doc.pop("updated_at", None)
            self._memory_graph = AgentGraph(**doc)
            logger.info("Memory graph loaded from DB (id=%s, vertices=%d)",
                        self._memory_graph.id, len(self._memory_graph.vertices))
            # Immediate cleanup on load — persist to KB + archive removed vertices
            removed = self.cleanup_memory_graph()
            if removed > 0:
                if hasattr(self, "_pending_archive") and self._pending_archive:
                    await self._persist_to_kb(self._pending_archive)
                    await self._archive_vertices(self._pending_archive)
                    self._pending_archive = []
                self._dirty.add(self._memory_graph.task_id)
                logger.info("Memory graph after cleanup: %d vertices", len(self._memory_graph.vertices))
            return self._memory_graph

        # Create new memory graph
        from app.agent.graph import create_memory_graph
        self._memory_graph = create_memory_graph(client_id)
        self._dirty.add(self._memory_graph.task_id)
        logger.info("New memory graph created (id=%s)", self._memory_graph.id)
        return self._memory_graph

    def get_memory_graph_cached(self) -> AgentGraph | None:
        """Get memory graph from RAM only (no DB fallback). For sync callers."""
        return self._memory_graph

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

    # --- Task parent tracking (child → parent nesting in memory graph) ---

    def register_task_parent(self, child_task_id: str, parent_task_id: str) -> None:
        """Register a child task's parent for memory graph nesting."""
        self._task_parent_map[child_task_id] = parent_task_id
        logger.debug("Task parent registered: %s → %s", child_task_id, parent_task_id)

    def get_task_parent(self, child_task_id: str) -> str | None:
        """Get parent task_id for a child task."""
        return self._task_parent_map.get(child_task_id)

    def _find_parent_vertex_id(self, parent_task_id: str) -> str | None:
        """Find the memory graph vertex ID for a parent task_id."""
        if not self._memory_graph:
            return None
        for v in self._memory_graph.vertices.values():
            if v.vertex_type == VertexType.TASK_REF and v.input_request == parent_task_id:
                return v.id
        return None

    # --- Link master graph ↔ sub-graph ---

    async def link_thinking_graph(
        self,
        task_id: str,
        sub_graph_id: str,
        title: str,
        completed: bool = False,
        failed: bool = False,
        result_summary: str = "",
        client_id: str = "",
        client_name: str = "",
        group_id: str | None = None,
        group_name: str = "",
        project_id: str | None = None,
        project_name: str = "",
        agent_type: str | None = None,
    ) -> None:
        """Link a task sub-graph to the memory graph via a TASK_REF vertex.

        Parent resolution order:
        1. Task parent (sub-task nesting via _task_parent_map)
        2. Client/group/project hierarchy (auto-creates CLIENT/GROUP/PROJECT vertices)
        """
        master = await self.get_or_create_memory_graph()
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
            group_id=group_id, group_name=group_name,
            project_id=project_id, project_name=project_name,
            agent_type=agent_type,
        )
        self._dirty.add(master.task_id)

        # Notify UI about memory graph change
        from app.tools.kotlin_client import kotlin_client
        try:
            await kotlin_client.notify_memory_graph_changed()
        except Exception:
            pass  # Non-fatal

    # --- ASK_USER helpers ---

    async def find_ask_user_vertices(self, client_id: str = "") -> list[tuple[str, GraphVertex]]:
        """Find all BLOCKED ASK_USER vertices across master graph + cached sub-graphs.

        CLIENT ISOLATION (STRICT): client_id is required. Without it, returns
        empty list — never leak cross-client questions.

        Returns list of (graph_task_id, vertex) tuples.
        """
        if not client_id:
            return []

        results: list[tuple[str, GraphVertex]] = []

        # Check memory graph
        if self._memory_graph:
            for v in self._memory_graph.vertices.values():
                if v.status == VertexStatus.BLOCKED:
                    if v.client_id == client_id or self._vertex_in_client(v, client_id):
                        results.append((self._memory_graph.task_id, v))

        # Check cached sub-graphs
        for task_id, graph in self._subgraphs.items():
            for v in graph.vertices.values():
                if v.status == VertexStatus.BLOCKED:
                    if v.client_id == client_id:
                        results.append((task_id, v))

        return results

    def _vertex_in_client(self, vertex: GraphVertex, client_id: str) -> bool:
        """Walk parent chain in memory_graph to check client ownership (legacy fallback)."""
        if not self._memory_graph:
            return False
        current = vertex
        for _ in range(5):
            if not current.parent_id:
                return False
            parent = self._memory_graph.vertices.get(current.parent_id)
            if not parent:
                return False
            if parent.vertex_type == VertexType.CLIENT:
                return parent.input_request == client_id
            current = parent
        return False

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
        if self._memory_graph and self._memory_graph.task_id == task_id:
            graph = self._memory_graph
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

    # --- Memory graph cleanup ---

    _ACTIVE_STATUSES = {
        VertexStatus.PENDING, VertexStatus.READY,
        VertexStatus.RUNNING, VertexStatus.BLOCKED,
    }

    _STALE_RUNNING_AGE_S = 30 * 60  # 30 min — RUNNING TASK_REF/INCOMING is stale
    _STALE_REQUEST_AGE_S = 10 * 60  # 10 min — RUNNING REQUEST is stale (chat SSE broke)

    def _mark_stale_running_vertices(self) -> int:
        """Mark stale RUNNING vertices as FAILED.

        Handles two scenarios:
        1. TASK_REF/INCOMING: orchestrator crashed mid-execution (30min threshold)
        2. REQUEST: SSE stream broke during chat (10min threshold)

        After a pod restart, these vertices have no worker — they'll be RUNNING
        forever unless we detect and fail them. The result_summary is preserved
        (if any partial response was saved), and the error explains what happened.
        """
        if not self._memory_graph:
            return 0
        graph = self._memory_graph
        now = datetime.now(timezone.utc)
        task_cutoff = (now - timedelta(seconds=self._STALE_RUNNING_AGE_S)).isoformat()
        request_cutoff = (now - timedelta(seconds=self._STALE_REQUEST_AGE_S)).isoformat()
        count = 0

        stale_types = {
            VertexType.TASK_REF: task_cutoff,
            VertexType.INCOMING: task_cutoff,
            VertexType.REQUEST: request_cutoff,
        }

        for v in graph.vertices.values():
            if v.status != VertexStatus.RUNNING:
                continue
            cutoff = stale_types.get(v.vertex_type)
            if cutoff is None:
                continue
            started = v.started_at or v.created_at or ""
            if started and started < cutoff:
                v.status = VertexStatus.FAILED
                v.error = "Stale — no completion signal (likely pod restart or SSE disconnect)"
                v.result_summary = v.result_summary or "Interrupted — no response received"
                v.completed_at = now.isoformat()
                count += 1
        if count:
            logger.info("Marked %d stale RUNNING vertices as FAILED", count)
        return count

    def cleanup_memory_graph(self) -> int:
        """Remove old completed/failed vertices from memory graph (per-client).

        3-tier lifecycle:
        - Tier 1 (RAM): active vertices + last N per client, max 24h after completion
        - Tier 2 (MongoDB archive): 7 days, per-vertex documents
        - Tier 3 (KB): permanent, content indexed before removal

        Keeps per client:
        - All active vertices (PENDING, READY, RUNNING, BLOCKED)
        - Last _KEEP_CHAT_PER_CLIENT REQUEST vertices
        - Last _KEEP_TASK_PER_CLIENT completed/failed TASK_REF vertices
        - Root vertex always

        Hierarchy GC: CLIENT/GROUP/PROJECT vertices are removed when they have
        no remaining data vertex descendants.

        Returns number of removed vertices.
        """
        if not self._memory_graph:
            return 0

        self._mark_stale_running_vertices()

        graph = self._memory_graph
        keep_ids: set[str] = set()
        hierarchy_types = {VertexType.CLIENT, VertexType.GROUP, VertexType.PROJECT}

        # Always keep root
        if graph.root_vertex_id:
            keep_ids.add(graph.root_vertex_id)

        # Always keep active (non-terminal) vertices regardless of client
        for vid, v in graph.vertices.items():
            if v.status in self._ACTIVE_STATUSES:
                keep_ids.add(vid)

        # --- Per-client cleanup ---
        # Group data vertices by client_id
        from collections import defaultdict
        client_chats: dict[str, list[tuple[str, GraphVertex]]] = defaultdict(list)
        client_tasks: dict[str, list[tuple[str, GraphVertex]]] = defaultdict(list)

        for vid, v in graph.vertices.items():
            if v.vertex_type in hierarchy_types or v.vertex_type == VertexType.ROOT:
                continue
            cid = v.client_id or self._resolve_client_id(v)
            if v.vertex_type == VertexType.REQUEST:
                client_chats[cid].append((vid, v))
            elif v.vertex_type == VertexType.TASK_REF:
                client_tasks[cid].append((vid, v))

        cutoff = (datetime.now(timezone.utc) - timedelta(seconds=_MAX_COMPLETED_AGE_S)).isoformat()

        def _keep_per_client(items: list[tuple[str, GraphVertex]], limit: int) -> None:
            """Keep last N items per client, respecting 24h cutoff."""
            sorted_items = sorted(items, key=lambda x: x[1].completed_at or "0", reverse=True)
            kept = 0
            for vid, v in sorted_items:
                if vid in keep_ids:
                    kept += 1
                    continue
                if kept >= limit:
                    continue
                # 24h cutoff — but always keep at least _MIN_KEEP_PER_CLIENT
                completed = v.completed_at or v.started_at or ""
                if completed and completed < cutoff and kept >= _MIN_KEEP_PER_CLIENT:
                    continue
                keep_ids.add(vid)
                kept += 1

        for cid, chats in client_chats.items():
            _keep_per_client(chats, _KEEP_CHAT_PER_CLIENT)
        for cid, tasks in client_tasks.items():
            _keep_per_client(tasks, _KEEP_TASK_PER_CLIENT)

        # Also keep INCOMING vertices within 24h
        for vid, v in graph.vertices.items():
            if v.vertex_type == VertexType.INCOMING and vid not in keep_ids:
                completed = v.completed_at or v.started_at or ""
                if not completed or completed >= cutoff:
                    keep_ids.add(vid)

        # --- Hierarchy GC ---
        # Keep hierarchy vertices only if they have at least one data descendant in keep_ids
        referenced_hierarchy: set[str] = set()
        for vid in keep_ids:
            v = graph.vertices.get(vid)
            if not v or v.vertex_type in hierarchy_types or v.vertex_type == VertexType.ROOT:
                continue
            # Walk parent chain and mark hierarchy ancestors as referenced
            current = v
            for _ in range(5):
                if not current.parent_id:
                    break
                parent = graph.vertices.get(current.parent_id)
                if not parent:
                    break
                if parent.vertex_type in hierarchy_types:
                    referenced_hierarchy.add(parent.id)
                current = parent

        for vid, v in graph.vertices.items():
            if v.vertex_type in hierarchy_types:
                if vid in referenced_hierarchy:
                    keep_ids.add(vid)
                # else: hierarchy vertex has no descendants → will be removed

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
            "Memory graph cleanup: removed %d vertices (kept %d, clients=%d)",
            len(to_remove), len(keep_ids),
            len(set(v.client_id for v in graph.vertices.values() if v.client_id)),
        )
        return len(to_remove)

    def _resolve_client_id(self, vertex: GraphVertex) -> str:
        """Resolve client_id for a vertex by walking parent chain (legacy fallback)."""
        if not self._memory_graph:
            return ""
        current = vertex
        for _ in range(5):
            if not current.parent_id:
                return ""
            parent = self._memory_graph.vertices.get(current.parent_id)
            if not parent:
                return ""
            if parent.vertex_type == VertexType.CLIENT:
                return parent.input_request or parent.client_id or ""
            current = parent
        return ""

    def cleanup_thinking_graphs(self) -> int:
        """Remove completed thinking graphs from RAM after _THINKING_GRAPH_TTL_S.

        Does NOT delete from MongoDB (30d TTL handles that).
        Returns number of evicted sub-graphs.
        """
        if not self._subgraphs:
            return 0

        now = datetime.now(timezone.utc)
        cutoff = (now - timedelta(seconds=_THINKING_GRAPH_TTL_S)).isoformat()
        to_evict: list[str] = []

        for task_id, graph in self._subgraphs.items():
            if graph.status not in (GraphStatus.COMPLETED, GraphStatus.FAILED):
                continue
            completed = graph.completed_at or ""
            if completed and completed < cutoff:
                to_evict.append(task_id)

        for task_id in to_evict:
            del self._subgraphs[task_id]

        if to_evict:
            logger.info("Evicted %d completed thinking graphs from RAM", len(to_evict))
        return len(to_evict)

    async def _persist_to_kb(self, vertices: list[dict]) -> None:
        """Persist vertex content to KB (Tier 3 — permanent).

        Indexes rich content so Jervis can "remember" what happened:
        - REQUEST: user message + response summary
        - TASK_REF: task title + result + tools used
        - INCOMING: qualified item context + approach

        Threshold: any vertex with at least 20 chars of meaningful content.

        KB ingest dials the shared gRPC stub via `kb_client.ingest`; the
        per-vertex try/except already handles unavailable KB.
        """
        indexable_types = ("task_ref", "request", "incoming")
        persisted = 0

        for v in vertices:
            vtype = v.get("vertex_type", "")
            if vtype not in indexable_types:
                continue
            client_id = v.get("client_id", "")
            if not client_id:
                continue

            title = v.get("title", "Task")
            summary = v.get("result_summary") or ""
            input_req = v.get("input_request") or ""
            description = v.get("description") or ""
            error = v.get("error") or ""
            tools_used = v.get("tools_used") or []

            # At least 20 chars of meaningful content
            meaningful = summary or input_req or description
            if len(meaningful) < 20:
                continue

            # Build rich content for KB indexing
            parts = [f"# {title}\n"]
            parts.append(f"Type: {vtype}")
            parts.append(f"Status: {v.get('status')}")
            if v.get("started_at"):
                parts.append(f"Started: {v.get('started_at')}")
            if v.get("completed_at"):
                parts.append(f"Completed: {v.get('completed_at')}")
            parts.append("")
            if input_req and input_req != title:
                parts.append(f"## Request\n{input_req}\n")
            if description and description != title and description != input_req:
                parts.append(f"## Description\n{description}\n")
            if summary:
                parts.append(f"## Result\n{summary}\n")
            if error:
                parts.append(f"## Error\n{error}\n")
            if tools_used:
                parts.append(f"Tools: {', '.join(str(t) for t in tools_used)}")

            content = "\n".join(parts)
            project_id = v.get("project_id") or ""

            try:
                from jervis_contracts import kb_client

                await kb_client.ingest(
                    caller="orchestrator.agent.persistence",
                    source_urn=f"agent://memory-graph/{v.get('id')}",
                    content=content,
                    client_id=client_id,
                    project_id=project_id or "",
                    kind="task_summary",
                    metadata={
                        "vertex_type": vtype,
                        "archived_from": "memory_graph",
                    },
                    timeout=10.0,
                )
                persisted += 1
            except Exception as e:
                logger.debug("KB persist failed for %s: %s", v.get("id"), e)

        if persisted:
            logger.info("Persisted %d vertices to KB (Tier 3)", persisted)

    async def _archive_vertices(self, vertices: list[dict]) -> None:
        """Archive removed vertices to MongoDB (Tier 2 — 7 days).

        Stores per-vertex documents with full data. TTL index handles
        automatic deletion after _ARCHIVE_TTL_DAYS.
        """
        if not vertices:
            return
        # Skip hierarchy/root vertices — not useful in archive
        indexable_types = ("task_ref", "request", "incoming")
        try:
            if self._archive_coll is None:
                coll = await self._ensure_collection()
                self._archive_coll = coll.database["master_graph_archive"]

            now = datetime.now(timezone.utc)
            docs = []
            for v in vertices:
                if v.get("vertex_type") not in indexable_types:
                    continue
                if not v.get("client_id"):
                    continue
                docs.append({
                    "vertex_id": v.get("id"),
                    "title": v.get("title", ""),
                    "vertex_type": v.get("vertex_type", ""),
                    "status": v.get("status", ""),
                    "client_id": v.get("client_id", ""),
                    "project_id": v.get("project_id", ""),
                    "input_request": v.get("input_request", ""),
                    "description": v.get("description", ""),
                    "result_summary": v.get("result_summary", ""),
                    "error": v.get("error", ""),
                    "tools_used": v.get("tools_used", []),
                    "started_at": v.get("started_at"),
                    "completed_at": v.get("completed_at"),
                    "archived_at": now,
                })

            if docs:
                await self._archive_coll.insert_many(docs)
                logger.info("Archived %d vertices to master_graph_archive (Tier 2)", len(docs))
        except Exception as e:
            logger.warning("Failed to archive vertices: %s", e)

    async def _periodic_cleanup(self) -> None:
        """Background task: cleanup memory graph + thinking graphs every N seconds."""
        while True:
            try:
                await asyncio.sleep(_CLEANUP_INTERVAL_S)

                # 1. Memory graph cleanup (per-client, 24h lifecycle)
                removed = self.cleanup_memory_graph()
                if removed > 0:
                    if self._pending_archive:
                        await self._persist_to_kb(self._pending_archive)
                        await self._archive_vertices(self._pending_archive)
                        self._pending_archive = []
                    await self.flush_dirty()

                # 2. Thinking graph RAM eviction (1h after completion)
                self.cleanup_thinking_graphs()

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error("Memory graph cleanup error: %s", e)

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
            if self._memory_graph and self._memory_graph.task_id == task_id:
                graph = self._memory_graph
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

    # --- Tier 2: Archive search ---

    async def search_archive(
        self,
        query: str,
        client_id: str,
        limit: int = 10,
    ) -> list[dict]:
        """Search the 7-day MongoDB archive (Tier 2).

        Uses MongoDB text search on title, result_summary, input_request.
        Client isolation enforced.
        """
        if not client_id:
            return []
        if self._archive_coll is None:
            coll = await self._ensure_collection()
            self._archive_coll = coll.database["master_graph_archive"]

        try:
            cursor = self._archive_coll.find(
                {
                    "$text": {"$search": query},
                    "client_id": client_id,
                },
                {"score": {"$meta": "textScore"}},
            ).sort([("score", {"$meta": "textScore"})]).limit(limit)
            results = []
            async for doc in cursor:
                doc.pop("_id", None)
                results.append(doc)
            return results
        except Exception as e:
            logger.warning("Archive search failed: %s", e)
            return []

    # --- Maintenance cycles (per-client progress tracking) ---

    async def _ensure_maintenance_coll(self) -> AsyncIOMotorCollection:
        if self._maintenance_coll is None:
            coll = await self._ensure_collection()
            self._maintenance_coll = coll.database["maintenance_cycles"]
        return self._maintenance_coll

    async def get_oldest_maintenance_client(
        self, task_type: str, all_client_ids: list[str],
    ) -> str | None:
        """Return client_id with the oldest maintenance cycle for given task_type.

        Clients never maintained are returned first. If all have been maintained,
        returns the one with the oldest last_run_at.
        """
        if not all_client_ids:
            return None
        coll = await self._ensure_maintenance_coll()

        # Find existing cycles for this task_type
        maintained: dict[str, str] = {}  # client_id → last_run_at
        async for doc in coll.find({"task_type": task_type}):
            maintained[doc["client_id"]] = doc.get("last_run_at", "")

        # Clients never maintained → pick first
        never_maintained = [cid for cid in all_client_ids if cid not in maintained]
        if never_maintained:
            return never_maintained[0]

        # All maintained → return oldest
        return min(all_client_ids, key=lambda cid: maintained.get(cid, ""))

    async def update_maintenance_cycle(
        self, client_id: str, task_type: str, result_summary: str,
    ) -> None:
        """Upsert maintenance cycle record."""
        coll = await self._ensure_maintenance_coll()
        await coll.update_one(
            {"client_id": client_id, "task_type": task_type},
            {
                "$set": {
                    "client_id": client_id,
                    "task_type": task_type,
                    "last_run_at": datetime.now(timezone.utc).isoformat(),
                    "result_summary": result_summary,
                },
                "$inc": {"run_count": 1},
            },
            upsert=True,
        )

    async def all_clients_fresh(
        self, task_type: str, all_client_ids: list[str], max_age_hours: int = 24,
    ) -> bool:
        """True if ALL clients have a maintenance cycle newer than max_age_hours."""
        if not all_client_ids:
            return True
        coll = await self._ensure_maintenance_coll()
        cutoff = (datetime.now(timezone.utc) - timedelta(hours=max_age_hours)).isoformat()
        fresh_count = await coll.count_documents({
            "task_type": task_type,
            "client_id": {"$in": all_client_ids},
            "last_run_at": {"$gte": cutoff},
        })
        return fresh_count >= len(all_client_ids)

    # --- Vertex task correlations (persistent) ---

    async def save_vertex_correlation(
        self, task_id: str, graph_id: str, vertex_id: str, session_id: str,
    ) -> None:
        """Persist vertex-task correlation to MongoDB."""
        if not self._vertex_tasks_coll:
            return
        await self._vertex_tasks_coll.replace_one(
            {"_id": task_id},
            {
                "_id": task_id,
                "graph_id": graph_id,
                "vertex_id": vertex_id,
                "session_id": session_id,
                "created_at": datetime.now(timezone.utc),
            },
            upsert=True,
        )

    async def pop_vertex_correlation(
        self, task_id: str,
    ) -> tuple[str, str, str] | None:
        """Load and delete vertex-task correlation. Returns (graph_id, vertex_id, session_id) or None."""
        if not self._vertex_tasks_coll:
            return None
        doc = await self._vertex_tasks_coll.find_one_and_delete({"_id": task_id})
        if not doc:
            return None
        return doc["graph_id"], doc["vertex_id"], doc["session_id"]


# Singleton
agent_store = AgentStore()
