"""MongoDB-based hierarchical context store for orchestrator.

Stores operational data (plans, step results, summaries, agent responses)
in MongoDB. Domain knowledge stays in KB (Weaviate + ArangoDB).

Collection: orchestrator_context
Index: (task_id, scope, scope_key) for fast hierarchy lookup
TTL: 30 days auto-expire
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorCollection

from app.config import settings
from app.context.agent_result_parser import parse_agent_result

logger = logging.getLogger(__name__)

_COLLECTION_NAME = "orchestrator_context"
_TTL_DAYS = 30


class ContextStore:
    """MongoDB-based hierarchical context store."""

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
            [("task_id", 1), ("scope", 1), ("scope_key", 1)],
            unique=True,
        )
        await self._collection.create_index(
            [("created_at", 1)],
            expireAfterSeconds=_TTL_DAYS * 86400,
        )
        logger.info("Context store initialized (collection=%s, ttl=%dd)", _COLLECTION_NAME, _TTL_DAYS)

    async def close(self) -> None:
        """Close MongoDB connection."""
        if self._client:
            self._client.close()
            self._client = None
            self._collection = None
        logger.info("Context store closed")

    @property
    def collection(self) -> AsyncIOMotorCollection:
        if self._collection is None:
            raise RuntimeError("Context store not initialized. Call init() first.")
        return self._collection

    async def save(
        self,
        task_id: str,
        scope: str,
        scope_key: str,
        summary: str,
        detail: Any = None,
    ) -> None:
        """Save a context fragment.

        Args:
            task_id: Task identifier.
            scope: "step" | "goal" | "epic" | "task" | "agent_result"
            scope_key: Hierarchical key, e.g. "goal/0/step/2"
            summary: 2-3 sentence summary (always present).
            detail: Full JSON detail (variadic structure).
        """
        doc = {
            "task_id": task_id,
            "scope": scope,
            "scope_key": scope_key,
            "summary": summary,
            "detail": detail,
            "created_at": datetime.now(timezone.utc),
            "ttl_days": _TTL_DAYS,
        }

        await self.collection.update_one(
            {"task_id": task_id, "scope": scope, "scope_key": scope_key},
            {"$set": doc},
            upsert=True,
        )

        logger.debug(
            "Context saved: task=%s scope=%s key=%s summary=%s",
            task_id, scope, scope_key, summary[:80],
        )

    async def get_summary(
        self, task_id: str, scope: str, scope_key: str,
    ) -> str | None:
        """Get summary for a specific context fragment."""
        doc = await self.collection.find_one(
            {"task_id": task_id, "scope": scope, "scope_key": scope_key},
            {"summary": 1},
        )
        return doc["summary"] if doc else None

    async def get_detail(
        self, task_id: str, scope: str, scope_key: str,
    ) -> dict | None:
        """Get full detail for a specific context fragment (on-demand fetch)."""
        doc = await self.collection.find_one(
            {"task_id": task_id, "scope": scope, "scope_key": scope_key},
            {"detail": 1},
        )
        return doc.get("detail") if doc else None

    async def list_summaries(
        self, task_id: str, scope: str,
    ) -> list[dict]:
        """List all summaries for a given scope (e.g., all step summaries).

        Returns list of {scope_key, summary} dicts.
        """
        cursor = self.collection.find(
            {"task_id": task_id, "scope": scope},
            {"scope_key": 1, "summary": 1, "_id": 0},
        ).sort("scope_key", 1)

        return await cursor.to_list(length=200)

    async def save_agent_result(
        self,
        task_id: str,
        scope_key: str,
        raw_response: Any,
    ) -> dict:
        """Normalize + save agent response. Returns {summary, changed_files}.

        Full raw response is stored in detail, only summary goes to LangGraph state.
        """
        parsed = await parse_agent_result(raw_response)

        await self.save(
            task_id=task_id,
            scope="agent_result",
            scope_key=scope_key,
            summary=parsed["summary"],
            detail={
                "success": parsed["success"],
                "changed_files": parsed["changed_files"],
                "raw": parsed["raw"],
            },
        )

        return {
            "summary": parsed["summary"],
            "changed_files": parsed["changed_files"],
            "success": parsed["success"],
        }


# Singleton instance
context_store = ContextStore()
