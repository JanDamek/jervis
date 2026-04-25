"""MongoDB-backed storage for Claude session compact snapshots.

A *compact snapshot* is a short free-form markdown document the Claude
session writes to its outbox as a final `type=note, meta.kind=compact`
event when asked to shut down (system event `COMPACT_AND_EXIT`). It is
the narrative bridge between consecutive sessions for the same scope —
complementary to the structured Memory Graph.

Collection: `compact_snapshots`
Document shape::

    {
        "_id": ObjectId(...),
        "scope": "client:68a332...",
        "content": "…markdown…",
        "client_id": "68a332...",
        "project_id": None,
        "session_id": "s-abc123",
        "snapshot_at": datetime,         # domain timestamp — Claude reads it
                                          # to know how old the narrative is
        "token_estimate": 1240,
    }

`snapshot_at` is a *domain* timestamp (input to Claude's freshness
judgement), not an audit field. Stored explicitly so the meaning is
unambiguous and it survives a re-import / re-shard where ObjectId
generation_time would lie.

Only the *latest* snapshot per scope is ever read when bootstrapping the
next session.
"""

from __future__ import annotations

import datetime
import logging
from dataclasses import dataclass

from bson import ObjectId
from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorDatabase

from app.config import estimate_tokens, settings

logger = logging.getLogger(__name__)

_client: AsyncIOMotorClient | None = None


def _db() -> AsyncIOMotorDatabase:
    global _client
    if _client is None:
        _client = AsyncIOMotorClient(settings.mongodb_url)
    return _client.get_default_database()


def _utc_now() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc)


def _as_aware_utc(value: datetime.datetime) -> datetime.datetime:
    """Mongo BSON datetimes come back naive (UTC-in-time, no tzinfo).
    Normalise to aware UTC so arithmetic with `now(tz=UTC)` doesn't raise.
    """
    if value.tzinfo is None:
        return value.replace(tzinfo=datetime.timezone.utc)
    return value


@dataclass
class CompactSnapshot:
    scope: str
    content: str
    client_id: str | None
    project_id: str | None
    session_id: str | None
    token_estimate: int
    # Domain timestamp — when this narrative was captured. Drives the
    # freshness hint in the next session's brief.
    snapshot_at: datetime.datetime

    @classmethod
    def from_doc(cls, doc: dict) -> "CompactSnapshot":
        snapshot_at = doc.get("snapshot_at")
        if snapshot_at is None:
            # Legacy rows written before snapshot_at existed — fall back to
            # the ObjectId's generation time. Acceptable approximation; new
            # writes always set the field explicitly.
            oid = doc.get("_id")
            snapshot_at = oid.generation_time if isinstance(oid, ObjectId) else _utc_now()
        return cls(
            scope=doc["scope"],
            content=doc.get("content", ""),
            client_id=doc.get("client_id"),
            project_id=doc.get("project_id"),
            session_id=doc.get("session_id"),
            token_estimate=int(doc.get("token_estimate", 0)),
            snapshot_at=_as_aware_utc(snapshot_at),
        )


def scope_for_client(client_id: str) -> str:
    return f"client:{client_id}"


SCOPE_GLOBAL = "global"


async def save_compact(
    *,
    scope: str,
    content: str,
    client_id: str | None = None,
    project_id: str | None = None,
    session_id: str | None = None,
) -> CompactSnapshot:
    """Insert a new compact snapshot. Does NOT delete previous ones."""
    if not content.strip():
        raise ValueError("compact content is empty")
    doc = {
        "scope": scope,
        "content": content,
        "client_id": client_id,
        "project_id": project_id,
        "session_id": session_id,
        "snapshot_at": _utc_now(),
        "token_estimate": estimate_tokens(content),
    }
    await _db()["compact_snapshots"].insert_one(doc)
    logger.info(
        "compact saved | scope=%s client=%s session=%s tokens=%d chars=%d",
        scope, client_id, session_id, doc["token_estimate"], len(content),
    )
    return CompactSnapshot.from_doc(doc)


async def load_latest(scope: str) -> CompactSnapshot | None:
    """Return the most recent snapshot for a scope or None."""
    doc = await _db()["compact_snapshots"].find_one(
        {"scope": scope},
        sort=[("snapshot_at", -1)],
    )
    if not doc:
        return None
    return CompactSnapshot.from_doc(doc)


async def prune_old(scope: str, keep_latest: int = 10) -> int:
    """Delete snapshots beyond the most recent `keep_latest` per scope.

    Run occasionally from maintenance; not on the hot path.
    """
    coll = _db()["compact_snapshots"]
    cursor = coll.find({"scope": scope}, sort=[("snapshot_at", -1)]).skip(keep_latest)
    ids = [d["_id"] async for d in cursor]
    if not ids:
        return 0
    res = await coll.delete_many({"_id": {"$in": ids}})
    logger.info("compact prune | scope=%s removed=%d", scope, res.deleted_count)
    return int(res.deleted_count)


async def ensure_indexes() -> None:
    """Idempotent index creation. Call once on service start.

    Covers the two collections owned by the pilot:
    - compact_snapshots: session narrative bridges.
    - claude_scratchpad: Claude's structured notebook (MCP writes here,
                         but the orchestrator owns index lifecycle).
    """
    db = _db()
    # Compact snapshots — fetch latest per scope by snapshot_at.
    compact = db["compact_snapshots"]
    await compact.create_index([("scope", 1), ("snapshot_at", -1)])
    await compact.create_index("client_id")
    # Scratchpad — primary key is (scope, namespace, key); also query by
    # scope alone (listing) and by tag for coarse filters.
    scratchpad = db["claude_scratchpad"]
    await scratchpad.create_index(
        [("scope", 1), ("namespace", 1), ("key", 1)],
        unique=True,
        name="scope_namespace_key_unique",
    )
    await scratchpad.create_index([("scope", 1), ("updated_at", -1)])
    await scratchpad.create_index("tags")
