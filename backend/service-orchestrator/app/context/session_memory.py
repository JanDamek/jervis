"""Session Memory — per-client/project short-term memory bridge.

Fast key-value lookup for recent decisions and context across orchestrations.
Unlike KB (semantic search, permanent), Session Memory is:
- Per client_id + project_id scope
- TTL 7 days
- Max 50 entries per scope
- Key-value lookup (not semantic search)

MongoDB collection: session_memory
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone, timedelta
from typing import Any

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorCollection

from app.config import settings
from app.models import SessionEntry, SessionMemoryPayload

logger = logging.getLogger(__name__)

_COLLECTION_NAME = "session_memory"


class SessionMemoryStore:
    """MongoDB-based session memory for cross-orchestration context."""

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._collection: AsyncIOMotorCollection | None = None

    async def init(self) -> None:
        """Initialize MongoDB connection and create indexes."""
        self._client = AsyncIOMotorClient(settings.mongodb_url)
        db = self._client.get_database("jervis")
        self._collection = db[_COLLECTION_NAME]

        # Create indexes
        await self._collection.create_index(
            [("client_id", 1), ("project_id", 1)],
            unique=True,
        )
        await self._collection.create_index(
            [("updated_at", 1)],
            expireAfterSeconds=settings.session_memory_ttl_days * 86400,
        )
        logger.info(
            "Session memory initialized (collection=%s, ttl=%dd, max=%d entries)",
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
            raise RuntimeError("Session memory not initialized. Call init() first.")
        return self._collection

    async def load(
        self,
        client_id: str,
        project_id: str | None = None,
    ) -> list[SessionEntry]:
        """Load session memory entries for a client/project pair.

        Returns list of SessionEntry objects, ordered by timestamp (newest first).
        """
        doc = await self.collection.find_one(
            {"client_id": client_id, "project_id": project_id or ""},
        )
        if not doc:
            return []

        entries: list[SessionEntry] = []
        for e in doc.get("entries", []):
            try:
                entries.append(SessionEntry(**e))
            except Exception:
                continue

        return entries

    async def append(
        self,
        client_id: str,
        project_id: str | None = None,
        entry: SessionEntry | None = None,
        *,
        source: str = "orchestrator_decision",
        summary: str = "",
        details: dict | None = None,
        task_id: str | None = None,
    ) -> None:
        """Append a new entry to session memory.

        If entry is None, builds one from keyword arguments.
        Enforces max_entries limit by trimming oldest entries.
        """
        if entry is None:
            entry = SessionEntry(
                timestamp=datetime.now(timezone.utc).isoformat(),
                source=source,
                summary=summary[:200],  # Enforce max 200 chars
                details=details,
                task_id=task_id,
            )

        entry_dict = entry.model_dump()
        project_key = project_id or ""

        # Upsert: push entry and trim to max
        await self.collection.update_one(
            {"client_id": client_id, "project_id": project_key},
            {
                "$push": {
                    "entries": {
                        "$each": [entry_dict],
                        "$slice": -settings.session_memory_max_entries,
                    },
                },
                "$set": {
                    "updated_at": datetime.now(timezone.utc),
                },
                "$setOnInsert": {
                    "client_id": client_id,
                    "project_id": project_key,
                },
            },
            upsert=True,
        )

        logger.debug(
            "Session memory append: client=%s project=%s summary=%s",
            client_id, project_key, entry.summary[:50],
        )

    async def clear(
        self,
        client_id: str,
        project_id: str | None = None,
    ) -> None:
        """Clear all session memory for a client/project pair."""
        await self.collection.delete_one(
            {"client_id": client_id, "project_id": project_id or ""},
        )

    async def get_context_text(
        self,
        client_id: str,
        project_id: str | None = None,
        max_entries: int = 20,
    ) -> str:
        """Get session memory as formatted text for LLM context.

        Returns a compact text block summarizing recent decisions.
        """
        entries = await self.load(client_id, project_id)
        if not entries:
            return ""

        # Take most recent entries
        recent = entries[-max_entries:]

        lines: list[str] = []
        for e in recent:
            source_tag = f"[{e.source}]" if e.source else ""
            lines.append(f"- {source_tag} {e.summary}")

        return "Recent decisions and context:\n" + "\n".join(lines)


# Singleton
session_memory_store = SessionMemoryStore()
