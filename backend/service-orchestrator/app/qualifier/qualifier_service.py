"""Orchestrates the qualifier pipeline.

PR-Q1 wired ``classify`` → ``audit``. PR-Q2 extends this same service
with the hint-event publication into the per-scope session inbox plus
the URGENT escape hatch (ad-hoc Claude consult when no live session is
available). PR-Q3 will add the ``propose_task`` driver. Keeping the
public surface a single ``process_event`` method lets call sites stay
stable across those PRs.
"""

from __future__ import annotations

import asyncio
import datetime
import logging
import time
import uuid

from app.qualifier.audit import audit_qualifier_decision
from app.qualifier.inbox import (
    QualifierHint,
    _db,
    new_hint_id,
    persist_hint,
    push_into_live_session,
    truncate_body,
    truncate_subject,
)
from app.qualifier.models import (
    QualifierClassification,
    QualifierDecision,
    QualifierEvent,
    QualifierUrgency,
)
from app.qualifier.proposal_emitter import ProposalDraft, draft_proposal_from_event
from app.qualifier.rules import classify_event

logger = logging.getLogger(__name__)


# Soft budget for the URGENT ad-hoc consult. Per plan: "30s fallback
# notification". After this we surface "URGENT bez Claude reasoning,
# manual review" via the existing push path; we do NOT cancel the future
# — a late response still writes back to the inbox.
_URGENT_CONSULT_BUDGET_SECONDS = 30.0


class QualifierService:
    """Stateless gate. Safe to call concurrently from any ingestion path."""

    async def process_event(self, evt: QualifierEvent) -> QualifierDecision:
        """Classify ``evt``, audit, then escalate if non-routine.

        Escalation path (PR-Q2):
        1. Build a :class:`QualifierHint` from ``evt`` + ``decision``.
        2. Always ``persist_hint`` (scratchpad inbox) — source of truth.
        3. If a live session exists, ``push_into_live_session`` for
           low-latency surfacing in the next turn.
        4. URGENT + no live session → spawn ad-hoc ANALYSIS Claude
           (out-of-band Anthropic call) with 30s soft budget; on
           timeout, fire user notification.
        """
        decision = await classify_event(evt)
        await audit_qualifier_decision(evt, decision)
        logger.debug(
            "qualifier | source=%s sender=%s classification=%s urgency=%s scope=%s",
            evt.source_kind.value,
            evt.sender,
            decision.classification.value,
            decision.urgency.value,
            decision.target_scope,
        )

        if (
            decision.classification == QualifierClassification.NON_ROUTINE
            and decision.target_scope
        ):
            hint = self._build_hint(evt, decision)
            await persist_hint(decision.target_scope, hint)
            pushed = await push_into_live_session(decision.target_scope, hint)

            # PR-Q3: heuristic auto-proposal. For event shapes whose
            # action is unambiguous (mail question, calendar invite,
            # bugtracker mention, Teams question) the qualifier drafts a
            # DRAFT-stage proposal up front so the session can refine
            # rather than draft from scratch. ``draft_proposal_from_event``
            # returns ``None`` for everything else and we stay
            # hint-only.
            draft = draft_proposal_from_event(evt, decision, hint)
            if draft is not None:
                # Fire-and-forget — the propose_task path does its own
                # gRPC retries / dedup. Never block the qualifier hot
                # path on the orchestrator → server round-trip.
                asyncio.create_task(
                    self._submit_qualifier_proposal(decision, draft, hint),
                    name=f"qualifier-propose-{hint.hint_id}",
                )

            if decision.urgency == QualifierUrgency.URGENT and not pushed:
                # No live session → out-of-band reasoning. Async future,
                # never blocks the qualifier hot path.
                asyncio.create_task(
                    self._urgent_adhoc_consult(evt, decision, hint),
                    name=f"qualifier-urgent-{hint.hint_id}",
                )

        return decision

    # ── hint construction ────────────────────────────────────────────

    def _build_hint(
        self,
        evt: QualifierEvent,
        decision: QualifierDecision,
    ) -> QualifierHint:
        return QualifierHint(
            hint_id=new_hint_id(),
            source_kind=evt.source_kind.value,
            sender=evt.sender,
            subject=truncate_subject(evt.subject),
            body=truncate_body(evt.body),
            classification=decision.classification.value,
            urgency=decision.urgency.value,
            rationale=decision.rationale,
            detected_client_id=decision.detected_client_id,
            detected_project_id=decision.detected_project_id,
            target_scope=decision.target_scope or "",
            ts=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        )

    # ── auto-proposal submission (PR-Q3) ─────────────────────────────

    async def _submit_qualifier_proposal(
        self,
        decision: QualifierDecision,
        draft: ProposalDraft,
        hint: QualifierHint,
    ) -> None:
        """Forward a heuristic :class:`ProposalDraft` to
        :func:`app.agent.proposal_service.propose_task`.

        The proposal_service handles embedding (router.Embed),
        3-tier semantic dedup (REJECT / SUGGEST_CONSOLIDATE / ALLOW) and
        the final Kotlin ``InsertProposal`` write — all the qualifier
        does is hand off the typed draft.

        On success the resulting ``task_id`` is back-linked into the
        existing inbox hint via :func:`_link_hint_to_proposal`, so the
        session sees a single inbox row with both the original event and
        a ``proposal_task_id`` pointer in one ``scratchpad_query`` call.
        """
        # Local import — keeps proposal_service (and the heavy gRPC stub
        # construction it pulls in) off the qualifier import chain.
        from app.agent.proposal_service import propose_task

        scheduled_at_iso = (
            draft.scheduled_at.isoformat() if draft.scheduled_at is not None else ""
        )

        try:
            result = await propose_task(
                client_id=decision.detected_client_id or "",
                project_id=decision.detected_project_id or "",
                title=draft.title,
                description=draft.description,
                reason=draft.reason,
                proposed_by=draft.proposed_by,
                proposal_task_type=draft.proposal_task_type,
                scheduled_at_iso=scheduled_at_iso,
                parent_task_id="",
                depends_on_task_ids=None,
            )
        except Exception:
            logger.exception(
                "qualifier proposal submit failed | scope=%s hint_id=%s",
                decision.target_scope, hint.hint_id,
            )
            return

        if result.ok:
            logger.info(
                "qualifier proposal submitted | scope=%s task=%s type=%s hint_id=%s",
                decision.target_scope,
                result.task_id,
                draft.proposal_task_type,
                hint.hint_id,
            )
            await _link_hint_to_proposal(
                decision.target_scope or "",
                hint.hint_id,
                result.task_id,
            )
        else:
            # Dedup REJECT / SUGGEST_CONSOLIDATE land here too — those
            # are not bugs, the conflicting task already lives in the
            # session's scope and the brief instructs the session to
            # update_proposed_task on the existing one. Surface the
            # decision/conflict in the log so an operator can audit.
            logger.warning(
                "qualifier proposal not inserted | scope=%s hint_id=%s decision=%s "
                "conflicting=%s err=%s",
                decision.target_scope,
                hint.hint_id,
                result.decision or "FAIL",
                result.conflicting_task_id or "-",
                result.error or "unknown",
            )

    # ── URGENT escape hatch ──────────────────────────────────────────

    async def _urgent_adhoc_consult(
        self,
        evt: QualifierEvent,
        decision: QualifierDecision,
        hint: QualifierHint,
    ) -> None:
        """URGENT classification + no live session = spawn ad-hoc Claude
        ANALYSIS-flavor reasoning with the hint context.

        This is an out-of-band Anthropic API call, NOT a session — we
        don't want to commit to spinning up a full per-scope SDK just for
        a one-shot urgency reasoner. 30s soft budget; if no response,
        emit a user notification "URGENT bez Claude reasoning, manual
        review" via :meth:`KotlinServerClient.send_push_notification`.
        We do NOT cancel the future — a late response still writes back
        to the scratchpad inbox as an additional hint so the next session
        turn can pick it up.
        """
        from anthropic import (
            APIConnectionError,
            APIStatusError,
            AsyncAnthropic,
            RateLimitError,
        )

        from app.config import settings

        if not getattr(settings, "anthropic_api_key", ""):
            logger.warning(
                "urgent consult skipped — no ANTHROPIC_API_KEY | scope=%s sender=%s",
                hint.target_scope, hint.sender,
            )
            await self._notify_urgent_unhandled(hint, reason="no_api_key")
            return

        system_prompt = (
            "You are Jervis qualifier urgency reasoner. Given an URGENT "
            "inbound event hint, return a one-line action recommendation "
            "(mail reply / dispatch / escalate / wait). Be terse — "
            "Czech allowed. No preamble, no explanation, just the line."
        )
        user_prompt = (
            f"URGENT hint from qualifier:\n"
            f"- source: {hint.source_kind}\n"
            f"- sender: {hint.sender}\n"
            f"- subject: {hint.subject}\n"
            f"- body: {hint.body}\n"
            f"- rationale: {hint.rationale}\n"
            f"- target scope: {hint.target_scope}\n\n"
            f"What should Jervis do RIGHT NOW?"
        )

        async def _call() -> str | None:
            try:
                client = AsyncAnthropic()
            except Exception:
                logger.exception("urgent consult: anthropic client init failed")
                return None
            model = getattr(settings, "compaction_model", None) or "claude-sonnet-4-6"
            try:
                resp = await client.messages.create(
                    model=model,
                    max_tokens=200,
                    system=system_prompt,
                    messages=[{"role": "user", "content": user_prompt}],
                )
            except (RateLimitError, APIConnectionError, APIStatusError) as e:
                logger.warning("urgent consult API error | err=%s", e)
                return None
            except Exception:
                logger.exception("urgent consult call failed")
                return None
            blocks = getattr(resp, "content", []) or []
            parts: list[str] = []
            for b in blocks:
                text = getattr(b, "text", None)
                if text:
                    parts.append(text)
            return ("\n".join(parts).strip()) or None

        started = time.monotonic()
        try:
            recommendation = await asyncio.wait_for(
                _call(), timeout=_URGENT_CONSULT_BUDGET_SECONDS,
            )
        except asyncio.TimeoutError:
            logger.warning(
                "urgent consult exceeded %ds budget | hint_id=%s scope=%s",
                int(_URGENT_CONSULT_BUDGET_SECONDS),
                hint.hint_id,
                hint.target_scope,
            )
            await self._notify_urgent_unhandled(hint, reason="timeout")
            return

        elapsed = time.monotonic() - started
        if not recommendation:
            logger.warning(
                "urgent consult returned no recommendation | elapsed=%.1fs hint_id=%s",
                elapsed, hint.hint_id,
            )
            await self._notify_urgent_unhandled(hint, reason="empty_response")
            return

        logger.info(
            "urgent consult ok | elapsed=%.1fs hint_id=%s scope=%s",
            elapsed, hint.hint_id, hint.target_scope,
        )

        # Write the reasoning back into the inbox as a follow-up hint so
        # the eventual session turn sees both the original event and the
        # ad-hoc recommendation in one ``scratchpad_query`` call.
        followup = QualifierHint(
            hint_id=str(uuid.uuid4()),
            source_kind=hint.source_kind,
            sender=hint.sender,
            subject=f"[urgent-consult] {hint.subject}",
            body=recommendation[:2000],
            classification=hint.classification,
            urgency=hint.urgency,
            rationale=f"Ad-hoc Claude consult on {hint.hint_id}",
            detected_client_id=hint.detected_client_id,
            detected_project_id=hint.detected_project_id,
            target_scope=hint.target_scope,
            ts=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        )
        await persist_hint(hint.target_scope, followup)
        # Also push if a session has come up in the meantime.
        await push_into_live_session(hint.target_scope, followup)

    async def _notify_urgent_unhandled(
        self,
        hint: QualifierHint,
        *,
        reason: str,
    ) -> None:
        """Fallback notification when URGENT could not get any Claude
        reasoning. Surfaces a Czech push to the user so they can manually
        review the inbound event. Uses the existing
        :meth:`KotlinServerClient.send_push_notification` helper — no new
        notification path introduced.
        """
        if not hint.detected_client_id:
            logger.warning(
                "urgent unhandled, no client_id for notification | hint_id=%s sender=%s reason=%s",
                hint.hint_id, hint.sender, reason,
            )
            return
        try:
            from app.tools.kotlin_client import kotlin_client

            title = "URGENT bez Claude reasoning"
            body = (
                f"Qualifier zachytil URGENT od {hint.sender}: "
                f"{hint.subject[:120]}. Vyžaduje manuální review."
            )
            data = {
                "kind": "qualifier_urgent_unhandled",
                "hintId": hint.hint_id,
                "scope": hint.target_scope,
                "sender": hint.sender,
                "reason": reason,
            }
            await kotlin_client.send_push_notification(
                client_id=hint.detected_client_id,
                title=title,
                body=body,
                data=data,
            )
        except Exception:
            logger.exception(
                "urgent unhandled push failed | hint_id=%s scope=%s",
                hint.hint_id, hint.target_scope,
            )


async def _link_hint_to_proposal(scope: str, hint_id: str, task_id: str) -> None:
    """Back-link the existing inbox hint to the freshly inserted proposal.

    PR-Q3: after :func:`app.agent.proposal_service.propose_task` lands a
    DRAFT-stage proposal, we ``$set`` the resulting ``task_id`` and an
    ``updated_at`` stamp on the existing scratchpad row so the session
    sees a single hint with a ``data.proposal_task_id`` pointer rather
    than two unrelated rows. The unique key is
    ``(scope, namespace, key)`` so the update is targeted.

    Failures are swallowed — the proposal itself is already persisted in
    the ``tasks`` collection (single source of truth), the back-link is
    just a UX convenience for the session.
    """
    if not scope or not hint_id or not task_id:
        return
    try:
        now = datetime.datetime.now(datetime.timezone.utc)
        await _db()["claude_scratchpad"].update_one(
            {"scope": scope, "namespace": "inbox", "key": hint_id},
            {
                "$set": {
                    "data.proposal_task_id": task_id,
                    "updated_at": now,
                },
            },
        )
    except Exception:
        logger.exception(
            "link_hint_to_proposal failed | scope=%s hint_id=%s task=%s",
            scope, hint_id, task_id,
        )


#: Module-level singleton — mirrors ``session_broker`` style. Importers
#: should depend on this rather than constructing their own instance so
#: the service stays a single shared object per process.
qualifier_service = QualifierService()
