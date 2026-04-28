"""Deterministic first-pass classification.

CPU-only on purpose: the qualifier must never block on the LLM router
queue. Anything an LLM would be needed for is ``NON_ROUTINE`` by default
and gets escalated to the per-scope Claude session (PR-Q2).
"""

from __future__ import annotations

import logging
import re

from motor.motor_asyncio import AsyncIOMotorClient, AsyncIOMotorDatabase

from app.config import settings
from app.qualifier.models import (
    QualifierClassification,
    QualifierDecision,
    QualifierEvent,
    QualifierUrgency,
)

logger = logging.getLogger(__name__)


# ─────────────────────────────────────────────────────────────────────
# Mongo singleton (mirrors the pattern in app.sessions.compact_store /
# app.sessions.session_broker — one motor client per process, lazy).
# ─────────────────────────────────────────────────────────────────────

_mongo: AsyncIOMotorClient | None = None


def _db() -> AsyncIOMotorDatabase:
    global _mongo
    if _mongo is None:
        _mongo = AsyncIOMotorClient(settings.mongodb_url)
    return _mongo.get_default_database()


# ─────────────────────────────────────────────────────────────────────
# Heuristic vocabularies — kept module-level so they are easy to tune
# without touching the function body. Lower-case throughout; matching is
# case-insensitive at the call site.
# ─────────────────────────────────────────────────────────────────────

_NOISE_SENDER_PREFIXES: tuple[str, ...] = (
    "no-reply@",
    "noreply@",
    "do-not-reply@",
    "donotreply@",
    "notifications@",
    "notification@",
    "daemon@",
    "mailer-daemon@",
    "postmaster@",
    "calendar-notification@",
    "jira-noreply@",
    "github-noreply@",
    "gitlab-noreply@",
    "bounces@",
)

_AUTO_REPLY_SUBJECT_TOKENS: tuple[str, ...] = (
    "out of office",
    "automatic reply",
    "automatická odpověď",
    "delivery status",
    "undeliverable",
    "read receipt",
    "mail delivery failed",
)

_CALENDAR_REPLY_SUBJECT_TOKENS: tuple[str, ...] = (
    "accepted:",
    "declined:",
    "tentatively accepted:",
    "tentative:",
    "přijato:",
    "odmítnuto:",
)

_CRYSTAL_CLEAR_ACK_BODIES: tuple[str, ...] = (
    "ok",
    "okay",
    "thanks",
    "thank you",
    "thx",
    "got it",
    "díky",
    "diky",
    "ok díky",
    "ok diky",
    "+1",
    "👍",
    "ack",
    "acknowledged",
    "noted",
)

_URGENT_SUBJECT_TOKENS: tuple[str, ...] = (
    "urgent",
    "asap",
    "deadline today",
    "deadline dnes",
    "okamžitě",
    "okamzite",
)

_HIGH_SUBJECT_TOKENS: tuple[str, ...] = (
    "important",
    "důležité",
    "dulezite",
    "prosím asap",
    "prosim asap",
    "high priority",
)

_LOW_BULK_PATTERNS: tuple[str, ...] = (
    "newsletter",
    "weekly digest",
    "monthly digest",
    "unsubscribe",
)

_MENTION_RE = re.compile(r"@\w+")


# ─────────────────────────────────────────────────────────────────────
# Public API
# ─────────────────────────────────────────────────────────────────────

async def classify_event(evt: QualifierEvent) -> QualifierDecision:
    """Run the first-pass deterministic classifier on ``evt``.

    Order of decisions (first match wins for AUTO_HANDLE):

    1. Noise sender prefix → ``AUTO_HANDLE`` / ``LOW``.
    2. Out-of-office / delivery-status / read-receipt subject →
       ``AUTO_HANDLE`` / ``LOW``.
    3. Calendar accept/decline reply with no extra body →
       ``AUTO_HANDLE`` / ``LOW``.
    4. Crystal-clear acknowledgement (``"OK"``, ``"Thanks"``, …, < 50
       chars, no ``@mention``) → ``AUTO_HANDLE`` / ``LOW``.
    5. Otherwise → ``NON_ROUTINE``. Urgency derived from subject tokens
       (URGENT > HIGH > LOW > NORMAL). Routing is resolved against
       ``connections.senderClientMappings`` /
       ``connections.domainClientMappings``.
    """

    sender = (evt.sender or "").strip().lower()
    subject = (evt.subject or "").strip().lower()
    body_stripped = (evt.body or "").strip()
    body_lower = body_stripped.lower()

    # 1. Noise senders — drop early without a Mongo lookup.
    for prefix in _NOISE_SENDER_PREFIXES:
        if sender.startswith(prefix):
            return QualifierDecision(
                classification=QualifierClassification.AUTO_HANDLE,
                urgency=QualifierUrgency.LOW,
                target_scope=None,
                rationale=f"noise sender prefix {prefix!r}",
                detected_client_id=None,
                detected_project_id=None,
            )

    # 2. Auto-reply / bounce subject.
    for token in _AUTO_REPLY_SUBJECT_TOKENS:
        if token in subject:
            return QualifierDecision(
                classification=QualifierClassification.AUTO_HANDLE,
                urgency=QualifierUrgency.LOW,
                target_scope=None,
                rationale=f"auto-reply subject token {token!r}",
                detected_client_id=None,
                detected_project_id=None,
            )

    # 3. Calendar accept/decline reply with empty / boilerplate body.
    if any(subject.startswith(tok) for tok in _CALENDAR_REPLY_SUBJECT_TOKENS):
        if len(body_stripped) <= 80 and not _MENTION_RE.search(body_stripped):
            return QualifierDecision(
                classification=QualifierClassification.AUTO_HANDLE,
                urgency=QualifierUrgency.LOW,
                target_scope=None,
                rationale="calendar reply with no new content",
                detected_client_id=None,
                detected_project_id=None,
            )

    # 4. Crystal-clear ACK — short body, no mention, matches a known ACK.
    if (
        len(body_stripped) < 50
        and not _MENTION_RE.search(body_stripped)
        and body_lower in _CRYSTAL_CLEAR_ACK_BODIES
    ):
        return QualifierDecision(
            classification=QualifierClassification.AUTO_HANDLE,
            urgency=QualifierUrgency.LOW,
            target_scope=None,
            rationale="crystal-clear acknowledgement",
            detected_client_id=None,
            detected_project_id=None,
        )

    # 5. NON_ROUTINE — resolve scope and urgency.
    target_scope, client_id, project_id = await _resolve_scope_for_sender(sender)
    urgency = _infer_urgency(subject)

    if target_scope is None:
        rationale = (
            f"unknown sender — no senderClientMappings / domainClientMappings "
            f"match for {sender!r}; user notification path will pick this up"
        )
    else:
        rationale = f"escalate to {target_scope}; urgency={urgency.value}"

    return QualifierDecision(
        classification=QualifierClassification.NON_ROUTINE,
        urgency=urgency,
        target_scope=target_scope,
        rationale=rationale,
        detected_client_id=client_id,
        detected_project_id=project_id,
    )


# ─────────────────────────────────────────────────────────────────────
# Scope resolution
# ─────────────────────────────────────────────────────────────────────

async def _resolve_scope_for_sender(
    sender: str,
) -> tuple[str | None, str | None, str | None]:
    """Resolve ``sender`` → ``(target_scope, client_id, project_id)``.

    Returns ``(None, None, None)`` when no mapping is found.

    Schema reality (see :class:`com.jervis.connection.ConnectionDocument`)
    differs from the original brief — there is no ``connections.principalId``
    column. Sender-to-client routing is stored as embedded maps on the
    connection document:

    * ``senderClientMappings: Map<String, String>`` — exact-match sender →
      clientId (and ``"*"``-pattern entries; we only handle exact match
      here, pattern matching is the orchestrator's job).
    * ``domainClientMappings: Map<String, String>`` — domain → clientId.

    We query each map with ``"<map>.<key>": {$exists: True}`` so Mongo can
    use the field index without us pulling every connection. Domain
    fallback is attempted only if the exact-sender lookup fails.
    """
    if not sender:
        return None, None, None

    db = _db()
    coll = db["connections"]

    # 1. Exact sender → clientId mapping.
    sender_field = f"senderClientMappings.{sender}"
    doc = await coll.find_one(
        {sender_field: {"$exists": True}},
        projection={sender_field: 1},
    )
    if doc:
        client_id = _extract_dotted(doc, sender_field)
        if client_id:
            return f"client:{client_id}", str(client_id), None

    # 2. Domain → clientId fallback.
    domain = sender.rsplit("@", 1)[-1] if "@" in sender else ""
    if domain:
        domain_field = f"domainClientMappings.{domain}"
        doc = await coll.find_one(
            {domain_field: {"$exists": True}},
            projection={domain_field: 1},
        )
        if doc:
            client_id = _extract_dotted(doc, domain_field)
            if client_id:
                return f"client:{client_id}", str(client_id), None

    return None, None, None


def _extract_dotted(doc: dict, dotted_path: str) -> str | None:
    """Walk a dotted path inside ``doc`` (e.g. ``"a.b.c"``)."""
    current: object = doc
    for part in dotted_path.split("."):
        if not isinstance(current, dict):
            return None
        current = current.get(part)
        if current is None:
            return None
    return str(current) if current else None


def _infer_urgency(subject: str) -> QualifierUrgency:
    """Derive urgency from subject tokens; default ``NORMAL``."""
    for tok in _URGENT_SUBJECT_TOKENS:
        if tok in subject:
            return QualifierUrgency.URGENT
    for tok in _HIGH_SUBJECT_TOKENS:
        if tok in subject:
            return QualifierUrgency.HIGH
    for tok in _LOW_BULK_PATTERNS:
        if tok in subject:
            return QualifierUrgency.LOW
    return QualifierUrgency.NORMAL
