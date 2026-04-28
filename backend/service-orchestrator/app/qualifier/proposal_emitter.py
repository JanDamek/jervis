"""Heuristic auto-proposal drafting for the qualifier (PR-Q3).

PR-Q2 lands a :class:`QualifierHint` into the per-scope session inbox for
every NON_ROUTINE event. PR-Q3 takes one more step for events whose shape
is unambiguous enough to draft a concrete action up front: when the
qualifier classifies a mail / Teams DM as a question, a calendar invite,
or a bugtracker mention, it produces a deterministic draft via
:func:`draft_proposal_from_event` and the qualifier service forwards it
to :func:`app.agent.proposal_service.propose_task` (DRAFT stage).

The result is that by the time the per-scope Claude CLI session wakes up,
the inbox hint already has a ``proposal_task_id`` back-reference so the
session can immediately review the heuristic draft, refine it via
``update_proposed_task``, and call ``send_for_approval`` — instead of
producing the proposal from scratch every turn.

This module is **LLM-free**. Drafting is intentionally template / regex
based so the qualifier hot path stays cheap and predictable; the Claude
session is the one that turns the template into a real reply.
"""

from __future__ import annotations

import datetime
import logging
from dataclasses import dataclass

from app.qualifier.inbox import QualifierHint
from app.qualifier.models import (
    QualifierClassification,
    QualifierDecision,
    QualifierEvent,
    QualifierSourceKind,
)

logger = logging.getLogger(__name__)


# Trigger lexicon for "this looks like a question / request". Used for
# both EMAIL and TEAMS_* sources. Czech first because most user inbound
# is Czech; English variants follow for cross-language traffic.
_QUESTION_TRIGGERS: tuple[str, ...] = (
    "?",
    "prosím",
    "můžeš",
    "můžete",
    "potřebuji",
    "potřeboval",
    "potřebujeme",
    "could you",
    "would you",
    "please",
    "request",
    "kindly",
)


@dataclass
class ProposalDraft:
    """LLM-free heuristic draft.

    The Claude session can later refine via ``update_proposed_task`` —
    the proposal lives in DRAFT stage so it is mutable. Field names mirror
    :func:`app.agent.proposal_service.propose_task` arguments so the
    qualifier service can forward them directly.
    """

    title: str
    description: str           # action body — actual reply / triage text
    reason: str                # qualifier rationale (audit trail)
    proposal_task_type: str    # ProposalTaskType enum value (uppercased)
    scheduled_at: datetime.datetime | None
    proposed_by: str           # "qualifier+<scope>"


def draft_proposal_from_event(
    evt: QualifierEvent,
    decision: QualifierDecision,
    hint: QualifierHint,
) -> ProposalDraft | None:
    """First-pass deterministic draft.

    Returns ``None`` if no auto-action pattern matches — the qualifier
    falls back to "hint only" in that case and the session decides from
    scratch what (if anything) to propose.

    Decision matrix:

    * ``EMAIL`` with question/request triggers → MAIL_REPLY draft,
      scheduled +15 min.
    * ``CALENDAR_INVITE`` → CALENDAR_RESPONSE draft, ASAP (no schedule).
    * ``BUGTRACKER`` → BUGTRACKER_ENTRY triage draft, ASAP.
    * ``TEAMS_DM`` / ``TEAMS_CHANNEL`` with question/request triggers →
      TEAMS_REPLY draft, scheduled +10 min.
    * ``MEETING_TRANSCRIPT`` → hint only (post-meeting summary triage is
      session work, not direct action).
    * ``KB_INGEST`` → hint only.
    * Otherwise → hint only.
    """
    if (
        not decision.target_scope
        or decision.classification != QualifierClassification.NON_ROUTINE
    ):
        return None

    proposed_by = f"qualifier+{decision.target_scope}"
    base_reason = (
        f"qualifier:{decision.classification.value}/{decision.urgency.value} "
        f"| {decision.rationale}"
    )

    if evt.source_kind == QualifierSourceKind.EMAIL:
        if _looks_like_question(evt.subject, evt.body):
            return ProposalDraft(
                title=f"Reply to mail: {_clip(evt.subject, 80)}",
                description=_draft_mail_reply_body(evt),
                reason=base_reason,
                proposal_task_type="MAIL_REPLY",
                scheduled_at=_utc_now() + datetime.timedelta(minutes=15),
                proposed_by=proposed_by,
            )
        return None

    if evt.source_kind == QualifierSourceKind.CALENDAR_INVITE:
        return ProposalDraft(
            title=f"Calendar response: {_clip(evt.subject, 80)}",
            description=(
                f"Pozvánka na schůzku od {evt.sender}.\n\n"
                f"Téma: {evt.subject}\n\n"
                f"Obsah: {_clip(evt.body, 500)}\n\n"
                f"Doporučená odpověď: …"
            ),
            reason=base_reason,
            proposal_task_type="CALENDAR_RESPONSE",
            scheduled_at=None,  # respond ASAP
            proposed_by=proposed_by,
        )

    if evt.source_kind == QualifierSourceKind.BUGTRACKER:
        return ProposalDraft(
            title=f"Triage issue: {_clip(evt.subject, 80)}",
            description=(
                f"Issue z bugtrackeru.\n\n"
                f"Reporter: {evt.sender}\n"
                f"Subject: {evt.subject}\n\n"
                f"Detail:\n{_clip(evt.body, 1500)}"
            ),
            reason=base_reason,
            proposal_task_type="BUGTRACKER_ENTRY",
            scheduled_at=None,
            proposed_by=proposed_by,
        )

    if evt.source_kind == QualifierSourceKind.MEETING_TRANSCRIPT:
        # Post-meeting triage is session work, not a direct action.
        return None

    if evt.source_kind in (
        QualifierSourceKind.TEAMS_DM,
        QualifierSourceKind.TEAMS_CHANNEL,
    ):
        if _looks_like_question(evt.subject, evt.body):
            return ProposalDraft(
                title=f"Reply on Teams: {_clip(evt.subject, 80)}",
                description=_draft_teams_reply_body(evt),
                reason=base_reason,
                proposal_task_type="TEAMS_REPLY",
                scheduled_at=_utc_now() + datetime.timedelta(minutes=10),
                proposed_by=proposed_by,
            )
        return None

    return None


# ── helpers ────────────────────────────────────────────────────────────


def _looks_like_question(subject: str, body: str) -> bool:
    """Cheap heuristic — questions / requests typically carry a question
    mark, "prosím", "můžeš", "potřebuji", "could you", "please" etc.
    Mass newsletters / notifications usually don't. False positives are
    cheap (the Claude session reviews before send_for_approval); false
    negatives just mean the session has to draft from scratch.
    """
    text = (subject + " " + body[:1000]).lower()
    return any(t in text for t in _QUESTION_TRIGGERS)


def _draft_mail_reply_body(evt: QualifierEvent) -> str:
    """Template mail reply body. Czech for user-visible parts. The Claude
    session refines before send_for_approval."""
    return (
        f"Re: {evt.subject}\n\n"
        f"Reply to: {evt.sender}\n\n"
        f"--- Original message ---\n"
        f"{_clip(evt.body, 1500)}\n\n"
        f"--- Draft reply (refine before send) ---\n"
        f"Děkuji za zprávu. […]"
    )


def _draft_teams_reply_body(evt: QualifierEvent) -> str:
    return (
        f"Reply on Teams chat from {evt.sender}.\n\n"
        f"--- Original message ---\n"
        f"{_clip(evt.body, 1000)}\n\n"
        f"--- Draft reply (refine before send) ---\n"
        f"…"
    )


def _clip(s: str, n: int) -> str:
    if not s:
        return ""
    return s if len(s) <= n else s[:n]


def _utc_now() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc)
