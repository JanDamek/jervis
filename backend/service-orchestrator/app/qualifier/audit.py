"""Persist qualifier decisions to ``claude_scratchpad``.

Mirrors :meth:`app.sessions.session_broker.SessionBroker._audit` so we
inherit the same Mongo client lifecycle, the same ``scope`` /
``namespace`` / ``key`` document shape, and the same TTL-based pruning
(handled by Mongo / external maintenance — no code-side TTL clean-up
here).
"""

from __future__ import annotations

import datetime
import logging
import uuid

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorDatabase

from app.config import settings
from app.qualifier.models import QualifierDecision, QualifierEvent

logger = logging.getLogger(__name__)


_mongo: AsyncIOMotorClient | None = None


def _db() -> AsyncIOMotorDatabase:
    global _mongo
    if _mongo is None:
        _mongo = AsyncIOMotorClient(settings.mongodb_url)
    return _mongo.get_default_database()


def _utc_now() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc)


# Subject preview cap — keep audit rows small so listing the scratchpad
# stays cheap even if the qualifier sees a flood of large emails.
_SUBJECT_PREVIEW_MAX_CHARS = 200

# Days kept in scratchpad — same order of magnitude as broker audit
# (30 days) but qualifier rows are useful longer for false-positive
# review. 90 days fits comfortably under MongoDB native TTL.
_TTL_DAYS = 90


async def audit_qualifier_decision(
    evt: QualifierEvent,
    decision: QualifierDecision,
) -> None:
    """Append a single audit row for ``decision`` taken on ``evt``.

    Failure of the audit write is *swallowed*: classification is the hot
    path and must never break because Mongo is briefly unreachable. We
    log a debug line so the issue is visible without polluting the
    operational stream.
    """
    try:
        now = _utc_now()
        doc = {
            "scope": "qualifier",
            "namespace": "audit",
            "key": str(uuid.uuid4()),
            "data": {
                "source_kind": evt.source_kind.value,
                "sender": evt.sender,
                "subject": evt.subject[:_SUBJECT_PREVIEW_MAX_CHARS],
                "classification": decision.classification.value,
                "urgency": decision.urgency.value,
                "target_scope": decision.target_scope,
                "rationale": decision.rationale,
                "detected_client_id": decision.detected_client_id,
                "detected_project_id": decision.detected_project_id,
                "ts": now.isoformat(),
            },
            "tags": [
                "qualifier",
                decision.classification.value,
                decision.urgency.value,
            ],
            "ttl_days": _TTL_DAYS,
            "created_at": now,
            "updated_at": now,
        }
        await _db()["claude_scratchpad"].insert_one(doc)
    except Exception:
        logger.debug(
            "qualifier audit write failed source=%s classification=%s",
            evt.source_kind.value,
            decision.classification.value,
        )
