"""Delegation metrics collector — per-agent, per-delegation metrics.

Stores execution metrics in MongoDB for analysis and optimisation.
Tracks: duration, token count, LLM calls, success rates per agent.

Collection: delegation_metrics
TTL: 90 days
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorCollection

from app.config import settings
from app.models import DelegationMetrics

logger = logging.getLogger(__name__)

_COLLECTION_NAME = "delegation_metrics"
_TTL_DAYS = 90


class DelegationMetricsCollector:
    """MongoDB-backed metrics collector for delegation execution."""

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._collection: AsyncIOMotorCollection | None = None

    async def init(self) -> None:
        """Initialise MongoDB connection and create indexes."""
        self._client = AsyncIOMotorClient(settings.mongodb_url)
        db = self._client.get_database("jervis")
        self._collection = db[_COLLECTION_NAME]

        await self._collection.create_index(
            [("delegation_id", 1)], unique=True,
        )
        await self._collection.create_index(
            [("agent_name", 1), ("start_time", -1)],
        )
        await self._collection.create_index(
            [("recorded_at", 1)],
            expireAfterSeconds=_TTL_DAYS * 86400,
        )
        logger.info(
            "Delegation metrics initialised (collection=%s, ttl=%dd)",
            _COLLECTION_NAME, _TTL_DAYS,
        )

    async def close(self) -> None:
        """Close MongoDB connection."""
        if self._client:
            self._client.close()
            self._client = None
            self._collection = None

    @property
    def collection(self) -> AsyncIOMotorCollection:
        if self._collection is None:
            raise RuntimeError("Metrics collector not initialised. Call init() first.")
        return self._collection

    # ------------------------------------------------------------------
    # Recording
    # ------------------------------------------------------------------

    async def record_start(
        self, delegation_id: str, agent_name: str,
    ) -> None:
        """Record the start of a delegation execution."""
        try:
            await self.collection.update_one(
                {"delegation_id": delegation_id},
                {
                    "$set": {
                        "delegation_id": delegation_id,
                        "agent_name": agent_name,
                        "start_time": datetime.now(timezone.utc).isoformat(),
                        "success": False,
                        "recorded_at": datetime.now(timezone.utc),
                    },
                    "$setOnInsert": {
                        "token_count": 0,
                        "llm_calls": 0,
                        "sub_delegation_count": 0,
                    },
                },
                upsert=True,
            )
        except Exception as exc:
            logger.debug("Failed to record delegation start: %s", exc)

    async def record_end(
        self,
        delegation_id: str,
        success: bool,
        token_count: int = 0,
        llm_calls: int = 0,
        sub_delegation_count: int = 0,
    ) -> None:
        """Record the completion of a delegation execution."""
        try:
            await self.collection.update_one(
                {"delegation_id": delegation_id},
                {
                    "$set": {
                        "end_time": datetime.now(timezone.utc).isoformat(),
                        "success": success,
                        "token_count": token_count,
                        "llm_calls": llm_calls,
                        "sub_delegation_count": sub_delegation_count,
                        "recorded_at": datetime.now(timezone.utc),
                    },
                },
            )
        except Exception as exc:
            logger.debug("Failed to record delegation end: %s", exc)

    # ------------------------------------------------------------------
    # Queries
    # ------------------------------------------------------------------

    async def get_agent_stats(self, agent_name: str) -> dict:
        """Get aggregate statistics for an agent.

        Returns: {total, successful, failed, avg_duration_ms, avg_tokens}
        """
        try:
            pipeline = [
                {"$match": {"agent_name": agent_name}},
                {
                    "$group": {
                        "_id": "$agent_name",
                        "total": {"$sum": 1},
                        "successful": {
                            "$sum": {"$cond": ["$success", 1, 0]},
                        },
                        "avg_tokens": {"$avg": "$token_count"},
                        "avg_llm_calls": {"$avg": "$llm_calls"},
                    },
                },
            ]
            cursor = self.collection.aggregate(pipeline)
            results = await cursor.to_list(length=1)
            if results:
                r = results[0]
                return {
                    "agent_name": agent_name,
                    "total": r.get("total", 0),
                    "successful": r.get("successful", 0),
                    "failed": r.get("total", 0) - r.get("successful", 0),
                    "avg_tokens": int(r.get("avg_tokens", 0)),
                    "avg_llm_calls": round(r.get("avg_llm_calls", 0), 1),
                }
            return {"agent_name": agent_name, "total": 0}
        except Exception as exc:
            logger.debug("Failed to get agent stats: %s", exc)
            return {"agent_name": agent_name, "total": 0, "error": str(exc)}

    async def get_recent(self, limit: int = 20) -> list[dict]:
        """Get recent delegation metrics for monitoring dashboard."""
        try:
            cursor = self.collection.find(
                {}, {"_id": 0},
            ).sort("recorded_at", -1).limit(limit)
            return await cursor.to_list(length=limit)
        except Exception as exc:
            logger.debug("Failed to get recent metrics: %s", exc)
            return []


# Singleton
metrics_collector = DelegationMetricsCollector()
