"""Qualifier — gate at the Jervis ingestion boundary.

The qualifier sits between *every* external ingestion path (mail poller,
Teams scraper, meeting transcript writer, calendar invite watcher,
bugtracker webhook, KB ingest, …) and the per-client / per-project
Claude sessions managed by the :mod:`app.sessions` broker.

Responsibilities (PR-Q1 scope):

* **Classify** — first-pass deterministic heuristic decides whether an
  inbound event is :attr:`QualifierClassification.AUTO_HANDLE` (silent
  drop / boilerplate / noise) or
  :attr:`QualifierClassification.NON_ROUTINE` (must reach a human-in-the-
  loop session).
* **Route** — resolve the sender string to a target session scope using
  ``connections.senderClientMappings`` / ``domainClientMappings`` rather
  than a dedicated identity service (kept out of MVP per the Claude CLI
  hierarchy plan).
* **Audit** — every decision is appended to ``claude_scratchpad`` with
  ``scope='qualifier'`` so we can review false positives without standing
  up a separate Mongo collection.

Out of scope here:

* Escalation / hint events into the session inbox — that is **PR-Q2**.
* Driving ``propose_task`` from qualifier output — **PR-Q3**.
* Any LLM call. PR-Q1 is intentionally CPU-only so the qualifier never
  blocks on the router queue.

Sender identity is a plain ``str`` (email / Teams principalId / speaker
label). A future identity service may replace this with a typed handle
without touching call sites — the public surface here only takes
``QualifierEvent``.
"""

from app.qualifier.audit import audit_qualifier_decision
from app.qualifier.inbox import (
    QualifierHint,
    persist_hint,
    push_into_live_session,
)
from app.qualifier.models import (
    QualifierClassification,
    QualifierDecision,
    QualifierEvent,
    QualifierSourceKind,
    QualifierUrgency,
)
from app.qualifier.qualifier_service import QualifierService, qualifier_service
from app.qualifier.rules import classify_event

__all__ = [
    "QualifierClassification",
    "QualifierDecision",
    "QualifierEvent",
    "QualifierHint",
    "QualifierService",
    "QualifierSourceKind",
    "QualifierUrgency",
    "audit_qualifier_decision",
    "classify_event",
    "persist_hint",
    "push_into_live_session",
    "qualifier_service",
]
