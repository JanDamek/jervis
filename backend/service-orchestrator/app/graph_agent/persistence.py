"""MongoDB persistence for TaskGraph.

Stores the complete graph (vertices, edges) as a single document.
Supports:
- save/load full graph
- atomic vertex status updates (without rewriting entire document)
- edge payload updates
- TTL-based auto-cleanup (30 days)
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorCollection

from app.config import settings
from app.graph_agent.models import (
    EdgePayload,
    GraphEdge,
    GraphStatus,
    GraphVertex,
    TaskGraph,
    VertexStatus,
)

logger = logging.getLogger(__name__)

_COLLECTION_NAME = "task_graphs"
_TTL_DAYS = 30


class TaskGraphStore:
    """MongoDB-backed persistence for task graphs."""

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._collection: AsyncIOMotorCollection | None = None

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
            [("updated_at", 1)],
            expireAfterSeconds=_TTL_DAYS * 86400,
        )
        logger.info(
            "TaskGraphStore initialized (collection=%s, ttl=%dd)",
            _COLLECTION_NAME, _TTL_DAYS,
        )

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
             "created_at": 1, "root_vertex_id": 1},
        )
        return [doc async for doc in cursor]


# Singleton
task_graph_store = TaskGraphStore()
