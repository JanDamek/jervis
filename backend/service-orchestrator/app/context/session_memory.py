"""Session Memory — short-lived per-client/project memory between orchestrations.

Bridges the gap between Working Memory (LangGraph state, per-task) and
Semantic Memory (KB, permanent). Stores recent decisions, preferences,
and context that the orchestrator needs across consecutive tasks.

Collection: session_memory
TTL: 7 days (configurable)
Max entries: 50 per client/project pair
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorCollection

from app.config import settings
from app.models import SessionEntry, SessionMemoryPayload

logger = logging.getLogger(__name__)

_COLLECTION_NAME = "session_memory"


class SessionMemoryStore:
    """MongoDB-backed session memory with TTL and entry limits."""

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._collection: AsyncIOMotorCollection | None = None

    async def init(self) -> None:
        """Initialise MongoDB connection and create indexes."""
        self._client = AsyncIOMotorClient(settings.mongodb_url)
        db = self._client.get_database("jervis")
        self._collection = db[_COLLECTION_NAME]

        # Compound index for fast lookup
        await self._collection.create_index(
            [("client_id", 1), ("project_id", 1)],
        )
        # TTL index for auto-expiry
        await self._collection.create_index(
            [("updated_at", 1)],
            expireAfterSeconds=settings.session_memory_ttl_days * 86400,
        )
        logger.info(
            "Session memory initialised (collection=%s, ttl=%dd, max_entries=%d)",
            _COLLECTION_NAME,
            settings.session_memory_ttl_days,
            settings.session_memory_max_entries,
        )

    async def close(self) -> None:
        """Close MongoDB connection."""
        if self._client:
            self._client.close()
            self._client = None
            self._collection = None
        logger.info("Session memory closed")

    @property
    def collection(self) -> AsyncIOMotorCollection:
        if self._collection is None:
            raise RuntimeError("Session memory not initialised. Call init() first.")
        return self._collection

    # ------------------------------------------------------------------
    # Read
    # ------------------------------------------------------------------

    async def load(
        self, client_id: str, project_id: str | None = None,
    ) -> SessionMemoryPayload:
        """Load session memory for a client/project pair.

        Returns an empty payload if no entries exist.
        """
        doc = await self.collection.find_one(
            {"client_id": client_id, "project_id": project_id or ""},
        )
        if not doc:
            return SessionMemoryPayload(
                client_id=client_id, project_id=project_id,
            )

        entries = [
            SessionEntry(**e) for e in doc.get("entries", [])
        ]
        return SessionMemoryPayload(
            client_id=client_id,
            project_id=project_id,
            entries=entries,
        )

    # ------------------------------------------------------------------
    # Write
    # ------------------------------------------------------------------

    async def append(
        self,
        client_id: str,
        project_id: str | None,
        entry: SessionEntry,
    ) -> None:
        """Append a session entry, enforcing the max-entries limit.

        Oldest entries are evicted when the limit is reached.
        """
        key = {"client_id": client_id, "project_id": project_id or ""}
        now = datetime.now(timezone.utc)

        # Push entry and trim to max size in one atomic operation
        await self.collection.update_one(
            key,
            {
                "$push": {
                    "entries": {
                        "$each": [entry.model_dump()],
                        "$slice": -settings.session_memory_max_entries,
                    },
                },
                "$set": {"updated_at": now},
                "$setOnInsert": {"created_at": now},
            },
            upsert=True,
        )
        logger.debug(
            "Session entry appended: client=%s project=%s source=%s",
            client_id, project_id, entry.source,
        )

    # ------------------------------------------------------------------
    # Maintenance
    # ------------------------------------------------------------------

    async def cleanup_expired(self) -> int:
        """Manually remove entries older than TTL (TTL index handles this,
        but this can be called for immediate cleanup).

        Returns the number of documents deleted.
        """
        cutoff = datetime.now(timezone.utc) - timedelta(
            days=settings.session_memory_ttl_days,
        )
        result = await self.collection.delete_many(
            {"updated_at": {"$lt": cutoff}},
        )
        if result.deleted_count:
            logger.info("Session memory cleanup: removed %d expired documents", result.deleted_count)
        return result.deleted_count


# Singleton
session_memory_store = SessionMemoryStore()
