"""Typed dataclasses for qualifier inputs / outputs.

Kept deliberately small and free of Pydantic — the qualifier sits on the
hot ingestion path and these objects are passed by reference, never
serialized over the wire (audit serialization is handled in
:mod:`app.qualifier.audit`).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any


class QualifierClassification(Enum):
    """Top-level routing decision."""

    #: Spam / ACK / calendar noise — the qualifier handles it itself.
    #: No session is woken, no notification is sent.
    AUTO_HANDLE = "auto_handle"

    #: Anything that requires the per-client / per-project Claude session
    #: to look at it. The escalation itself is wired in PR-Q2.
    NON_ROUTINE = "non_routine"


class QualifierUrgency(Enum):
    """Urgency hint passed to downstream session inbox.

    Mapped 1:1 onto deadline-bucketed task urgency in the orchestrator;
    see :mod:`docs/architecture-task-routing-unified` for the canonical
    semantics.
    """

    LOW = "low"
    NORMAL = "normal"
    HIGH = "high"
    URGENT = "urgent"  # CEO escalation / deadline imminent


class QualifierSourceKind(Enum):
    """Where the inbound event came from.

    Used by audit consumers and by future PR-Q2 routing rules — the
    qualifier itself stays source-agnostic in PR-Q1.
    """

    EMAIL = "email"
    TEAMS_DM = "teams_dm"
    TEAMS_CHANNEL = "teams_channel"
    MEETING_TRANSCRIPT = "meeting_transcript"
    CALENDAR_INVITE = "calendar_invite"
    BUGTRACKER = "bugtracker"
    KB_INGEST = "kb_ingest"


@dataclass
class QualifierEvent:
    """Inbound event from any external ingestion path.

    ``sender`` is the canonical string identifier for the originating
    party — this is *intentionally* not a typed identity object so that
    every ingestion path can produce one without depending on a future
    identity service.

    Concrete formats per :class:`QualifierSourceKind`:

    * ``EMAIL`` → RFC-5322 address, lowercased.
    * ``TEAMS_DM`` / ``TEAMS_CHANNEL`` → Microsoft Graph principal id
      (``8:orgid:<uuid>`` or display name fallback).
    * ``MEETING_TRANSCRIPT`` → speaker label as emitted by diarization.
    * ``CALENDAR_INVITE`` → organizer email.
    * ``BUGTRACKER`` → reporter handle (``user@host``).
    * ``KB_INGEST`` → ``kb://<source_urn>``.
    """

    source_kind: QualifierSourceKind
    sender: str
    subject: str
    body: str
    timestamp: datetime
    raw_metadata: dict[str, Any] = field(default_factory=dict)


@dataclass
class QualifierDecision:
    """Output of :func:`app.qualifier.rules.classify_event`."""

    classification: QualifierClassification
    urgency: QualifierUrgency

    #: ``client:<cid>`` / ``project:<cid>:<pid>`` / ``None``.
    #:
    #: ``None`` means either an :attr:`QualifierClassification.AUTO_HANDLE`
    #: decision (no session needs to know) or an unknown sender that
    #: should fall back to the global user-notification path (handled in
    #: PR-Q2 — PR-Q1 only records the decision).
    target_scope: str | None

    rationale: str
    detected_client_id: str | None
    detected_project_id: str | None
