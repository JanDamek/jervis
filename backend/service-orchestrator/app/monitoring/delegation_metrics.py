"""Delegation metrics — per-agent, per-delegation performance tracking.

Collects metrics for monitoring, cost tracking, and performance optimization.
Stored in MongoDB for dashboard and analysis.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorCollection

from app.config import settings
from app.models import DelegationMetrics

logger = logging.getLogger(__name__)

_COLLECTION_NAME = "delegation_metrics"
_TTL_DAYS = 90  # Keep metrics for 90 days


class DelegationMetricsCollector:
    """Collect and store per-agent delegation metrics."""

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._collection: AsyncIOMotorCollection | None = None

    async def init(self) -> None:
        """Initialize MongoDB connection and create indexes."""
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
            [("created_at", 1)],
            expireAfterSeconds=_TTL_DAYS * 86400,
        )
        logger.info("Delegation metrics initialized (ttl=%dd)", _TTL_DAYS)

    async def close(self) -> None:
        """Close MongoDB connection."""
        if self._client:
            self._client.close()
            self._client = None
            self._collection = None

    @property
    def collection(self) -> AsyncIOMotorCollection:
        if self._collection is None:
            raise RuntimeError("Metrics collector not initialized.")
        return self._collection

    async def record_start(
        self,
        delegation_id: str,
        agent_name: str,
    ) -> None:
        """Record the start of a delegation execution."""
        now = datetime.now(timezone.utc)
        await self.collection.update_one(
            {"delegation_id": delegation_id},
            {
                "$set": {
                    "delegation_id": delegation_id,
                    "agent_name": agent_name,
                    "start_time": now.isoformat(),
                    "success": False,
                    "created_at": now,
                },
                "$setOnInsert": {
                    "token_count": 0,
                    "llm_calls": 0,
                    "sub_delegation_count": 0,
                },
            },
            upsert=True,
        )

    async def record_end(
        self,
        delegation_id: str,
        success: bool,
        token_count: int = 0,
        llm_calls: int = 0,
        sub_delegation_count: int = 0,
    ) -> None:
        """Record the end of a delegation execution."""
        now = datetime.now(timezone.utc)
        await self.collection.update_one(
            {"delegation_id": delegation_id},
            {
                "$set": {
                    "end_time": now.isoformat(),
                    "success": success,
                    "token_count": token_count,
                    "llm_calls": llm_calls,
                    "sub_delegation_count": sub_delegation_count,
                },
            },
        )

    async def get_agent_stats(
        self,
        agent_name: str,
        days: int = 30,
    ) -> dict:
        """Get aggregated stats for an agent over the last N days."""
        try:
            cutoff = datetime.now(timezone.utc).isoformat()
            pipeline = [
                {"$match": {
                    "agent_name": agent_name,
                    "end_time": {"$exists": True},
                }},
                {"$group": {
                    "_id": "$agent_name",
                    "total_executions": {"$sum": 1},
                    "successful": {"$sum": {"$cond": ["$success", 1, 0]}},
                    "total_tokens": {"$sum": "$token_count"},
                    "total_llm_calls": {"$sum": "$llm_calls"},
                    "avg_tokens": {"$avg": "$token_count"},
                }},
            ]
            cursor = self.collection.aggregate(pipeline)
            results = await cursor.to_list(length=1)

            if not results:
                return {
                    "agent_name": agent_name,
                    "total_executions": 0,
                    "success_rate": 0.0,
                    "total_tokens": 0,
                    "avg_tokens": 0,
                }

            r = results[0]
            total = r.get("total_executions", 0)
            successful = r.get("successful", 0)

            return {
                "agent_name": agent_name,
                "total_executions": total,
                "success_rate": successful / total if total > 0 else 0.0,
                "total_tokens": r.get("total_tokens", 0),
                "avg_tokens": int(r.get("avg_tokens", 0)),
                "total_llm_calls": r.get("total_llm_calls", 0),
            }
        except Exception as e:
            logger.debug("Failed to get agent stats: %s", e)
            return {"agent_name": agent_name, "error": str(e)}

    async def get_all_stats(self) -> list[dict]:
        """Get stats for all agents."""
        try:
            pipeline = [
                {"$match": {"end_time": {"$exists": True}}},
                {"$group": {
                    "_id": "$agent_name",
                    "total": {"$sum": 1},
                    "successful": {"$sum": {"$cond": ["$success", 1, 0]}},
                    "total_tokens": {"$sum": "$token_count"},
                }},
                {"$sort": {"total": -1}},
            ]
            cursor = self.collection.aggregate(pipeline)
            results = await cursor.to_list(length=50)

            return [
                {
                    "agent_name": r["_id"],
                    "total_executions": r["total"],
                    "success_rate": r["successful"] / r["total"] if r["total"] > 0 else 0.0,
                    "total_tokens": r.get("total_tokens", 0),
                }
                for r in results
            ]
        except Exception as e:
            logger.debug("Failed to get all stats: %s", e)
            return []


# Singleton
metrics_collector = DelegationMetricsCollector()
