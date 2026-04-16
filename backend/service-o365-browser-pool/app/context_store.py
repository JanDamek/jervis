"""Agent memory stores — pod_agent_patterns + pod_agent_memory.

Product §20 Layer C. Complements the raw LangGraph MongoDBSaver
checkpoint (Layer A) with a durable knowledge layer scoped to one
`connectionId`:

  - pod_agent_patterns: stable selectors + action templates learned
    over ≥3 successful uses. Loaded into the SystemMessage on cold
    start so the agent does not rediscover them every restart.

  - pod_agent_memory: distilled session summaries + learned rules +
    anomalies. Also loaded into SystemMessage.

Cross-connection sharing is explicitly disallowed — every connection
learns its own tenant A/B UI quirks.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Literal

from bson import ObjectId
from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorDatabase

from app.config import settings

logger = logging.getLogger("o365-browser-pool.context-store")


def _oid(value: str | None):
    if not value:
        return value
    try:
        return ObjectId(value)
    except Exception:
        return value


class ContextStore:
    """Motor-backed CRUD for pod_agent_patterns + pod_agent_memory."""

    def __init__(self) -> None:
        self._client: AsyncIOMotorClient | None = None
        self._db: AsyncIOMotorDatabase | None = None

    async def start(self) -> None:
        uri = (
            f"mongodb://{settings.mongodb_user}:{settings.mongodb_password}"
            f"@{settings.mongodb_host}:{settings.mongodb_port}"
            f"/{settings.mongodb_database}?authSource={settings.mongodb_auth_db}"
        )
        self._client = AsyncIOMotorClient(uri)
        self._db = self._client[settings.mongodb_database]

        patterns = self._db["pod_agent_patterns"]
        await patterns.create_index(
            [("connectionId", 1), ("urlPattern", 1)],
            unique=True, name="connection_pattern_unique",
        )
        await patterns.create_index(
            [("connectionId", 1), ("lastUsedAt", -1)],
            name="connection_lru_idx",
        )

        memory = self._db["pod_agent_memory"]
        await memory.create_index(
            [("connectionId", 1), ("kind", 1), ("createdAt", -1)],
            name="connection_kind_recency_idx",
        )

        logger.info("ContextStore connected to MongoDB")

    async def stop(self) -> None:
        if self._client is not None:
            self._client.close()
            self._client = None
            self._db = None

    # ---- Patterns --------------------------------------------------------

    async def upsert_pattern(
        self,
        *,
        connection_id: str,
        url_pattern: str,
        app_state: str,
        working_selectors: dict[str, str],
        action_template: list[str] | None = None,
        notes: str | None = None,
    ) -> None:
        """Upsert a working-selectors + action-template entry. Callers bump
        `observedCount` / `successCount` explicitly via bump_pattern below —
        this call just plants the record or updates the payload."""
        if self._db is None:
            return
        now = datetime.now(timezone.utc)
        await self._db["pod_agent_patterns"].update_one(
            {
                "connectionId": _oid(connection_id),
                "urlPattern": url_pattern,
            },
            {
                "$set": {
                    "appState": app_state,
                    "workingSelectors": working_selectors,
                    "actionTemplate": action_template or [],
                    "notes": notes,
                    "lastUsedAt": now,
                },
                "$setOnInsert": {
                    "connectionId": _oid(connection_id),
                    "urlPattern": url_pattern,
                    "observedCount": 0,
                    "successCount": 0,
                    "failureCount": 0,
                    "createdAt": now,
                },
            },
            upsert=True,
        )

    async def bump_pattern(
        self,
        *,
        connection_id: str,
        url_pattern: str,
        success: bool,
    ) -> None:
        if self._db is None:
            return
        now = datetime.now(timezone.utc)
        inc: dict = {"observedCount": 1}
        inc["successCount" if success else "failureCount"] = 1
        set_fields = {"lastUsedAt": now}
        if success:
            set_fields["lastSuccessAt"] = now
        await self._db["pod_agent_patterns"].update_one(
            {"connectionId": _oid(connection_id), "urlPattern": url_pattern},
            {"$inc": inc, "$set": set_fields},
        )

    async def list_top_patterns(
        self, *, connection_id: str, limit: int = 10,
    ) -> list[dict]:
        """Top patterns by `lastUsedAt`, excluding entries where
        `failureCount >= 3` AND `successCount < failureCount` (demoted).
        """
        if self._db is None:
            return []
        cursor = self._db["pod_agent_patterns"].find(
            {"connectionId": _oid(connection_id)},
            {"_id": 0},
        ).sort("lastUsedAt", -1).limit(limit * 2)
        out: list[dict] = []
        async for doc in cursor:
            failed = int(doc.get("failureCount") or 0)
            succeeded = int(doc.get("successCount") or 0)
            if failed >= 3 and succeeded < failed:
                continue
            out.append(doc)
            if len(out) >= limit:
                break
        return out

    # ---- Memory (sessions / rules / anomalies) ---------------------------

    async def insert_memory(
        self,
        *,
        connection_id: str,
        kind: Literal["session_summary", "learned_rule", "anomaly"],
        content: str,
        compressed_range: dict | None = None,
    ) -> None:
        if self._db is None:
            return
        now = datetime.now(timezone.utc)
        await self._db["pod_agent_memory"].insert_one(
            {
                "connectionId": _oid(connection_id),
                "kind": kind,
                "content": content,
                "compressedFromRange": compressed_range,
                "createdAt": now,
            },
        )

    async def latest_session_summary(
        self, *, connection_id: str,
    ) -> str | None:
        if self._db is None:
            return None
        doc = await self._db["pod_agent_memory"].find_one(
            {"connectionId": _oid(connection_id), "kind": "session_summary"},
            {"content": 1, "_id": 0},
            sort=[("createdAt", -1)],
        )
        if doc is None:
            return None
        return doc.get("content")

    async def recent_learned_rules(
        self, *, connection_id: str, limit: int = 5,
    ) -> list[str]:
        if self._db is None:
            return []
        cursor = self._db["pod_agent_memory"].find(
            {"connectionId": _oid(connection_id), "kind": "learned_rule"},
            {"content": 1, "_id": 0},
        ).sort("createdAt", -1).limit(limit)
        out: list[str] = []
        async for doc in cursor:
            content = doc.get("content")
            if content:
                out.append(content)
        return out


async def compose_cold_start_preamble(
    store: ContextStore, *, connection_id: str,
) -> str:
    """Build the per-connection memory block that goes into the
    SystemMessage on every outer-loop entry. Empty block on cold start
    with no prior learning."""
    summary = await store.latest_session_summary(connection_id=connection_id)
    patterns = await store.list_top_patterns(connection_id=connection_id, limit=10)
    rules = await store.recent_learned_rules(connection_id=connection_id, limit=5)

    if not summary and not patterns and not rules:
        return ""

    lines: list[str] = ["=================================================================",
                        "CONNECTION-SCOPED MEMORY (loaded every tick, never cached in state)",
                        "================================================================="]
    if summary:
        lines.append("")
        lines.append("Previous-session summary:")
        lines.append(summary.strip())
    if patterns:
        lines.append("")
        lines.append("Learned patterns for this connection (top 10):")
        for p in patterns:
            sel_bits = ", ".join(
                f"{k}={v}" for k, v in (p.get("workingSelectors") or {}).items()
            )
            lines.append(
                f"- {p['urlPattern']} [{p.get('appState', '?')}]: {sel_bits}"
            )
    if rules:
        lines.append("")
        lines.append("Learned rules (most recent 5):")
        for r in rules:
            lines.append(f"- {r.strip()}")
    lines.append("")
    return "\n".join(lines)
