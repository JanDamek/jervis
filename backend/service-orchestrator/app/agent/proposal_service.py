"""Claude CLI proposal lifecycle — write logic in the orchestrator.

Per the cross-service plan, the MCP server is a thin proxy and write
logic lives in the orchestrator. This module owns:

- text embedding via the router's RouterInferenceService.Embed
- 3-tier semantic dedup against recent proposals authored by the same
  scope (REJECT same scope cosine ≥ thresholds, SUGGEST_CONSOLIDATE
  same client different project, ALLOW different client)
- forwarding to the Kotlin ServerTaskProposalService for the actual
  Mongo write (atomic stage transitions, single writer for `tasks`)

Embedding fallback: if the router responds with a non-OK error or the
KB embed surface is unavailable, we degrade to "skip dedup, allow
through" rather than blocking proposals on a missing dependency. The
log line ``EMBED_UNAVAILABLE`` flags it for the operator. The Kotlin
side accepts empty embedding lists (descEmbedding/titleEmbedding
nullable) so this is safe.
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass

import grpc
from jervis.common import enums_pb2, types_pb2
from jervis.router import inference_pb2
from jervis.server import task_proposal_pb2

from app.grpc_server_client import (
    build_request_context as _server_ctx,
    server_task_proposal_stub,
)
from jervis_contracts.interceptors import prepare_context

logger = logging.getLogger(__name__)


# Cosine thresholds — title is shorter, more discriminative; description
# allows a bit of slack. Same-scope hits above these are treated as
# duplicates and the new propose is REJECTED with a hint pointing at
# the existing task.
TITLE_SIM_REJECT = 0.85
DESC_SIM_REJECT = 0.80


@dataclass
class DedupDecision:
    decision: str  # "ALLOW" | "REJECT" | "SUGGEST_CONSOLIDATE"
    conflicting_task_id: str = ""
    conflicting_title: str = ""
    title_sim: float = 0.0
    desc_sim: float = 0.0


@dataclass
class ProposeResult:
    ok: bool
    task_id: str = ""
    error: str = ""
    decision: str = ""             # filled when ok=False due to dedup
    conflicting_task_id: str = ""
    conflicting_title: str = ""


# ── embeddings ─────────────────────────────────────────────────────────


async def _embed_pair(
    title: str,
    description: str,
    *,
    client_id: str,
) -> tuple[list[float] | None, list[float] | None]:
    """Embed (title, description) in one round-trip.

    Returns (None, None) on any router failure — caller treats this as
    "skip dedup" rather than blocking the propose. Logs WARN so the
    operator notices repeated misses.
    """
    inputs = [
        title.strip() if title else "",
        description.strip() if description else "",
    ]
    if not any(inputs):
        return None, None
    # Local import — avoids loading the heavy provider module at import
    # time (it pulls grpc channels, tokenisers, etc).
    from app.llm.provider import _get_router_stub

    ctx = types_pb2.RequestContext(
        scope=types_pb2.Scope(client_id=client_id or ""),
        priority=enums_pb2.PRIORITY_FOREGROUND,
        capability=enums_pb2.CAPABILITY_EMBEDDING,
        intent="proposal_dedup",
    )
    prepare_context(ctx)
    try:
        stub = _get_router_stub()
        resp = await stub.Embed(
            inference_pb2.EmbedRequest(
                ctx=ctx,
                inputs=inputs,
            ),
        )
    except grpc.RpcError as e:
        logger.warning("EMBED_UNAVAILABLE proposal embed via router failed: %s", e)
        return None, None
    except Exception as e:
        logger.warning("EMBED_UNAVAILABLE proposal embed unexpected error: %s", e)
        return None, None

    vectors = list(resp.embeddings)
    if len(vectors) < 2:
        logger.warning(
            "EMBED_UNAVAILABLE router returned only %d vectors for 2 inputs", len(vectors)
        )
        return None, None
    title_vec = list(vectors[0].vector) or None
    desc_vec = list(vectors[1].vector) or None
    return title_vec, desc_vec


def _cosine(a: list[float] | None, b: list[float] | None) -> float:
    if not a or not b:
        return 0.0
    if len(a) != len(b):
        return 0.0
    dot = 0.0
    na = 0.0
    nb = 0.0
    for x, y in zip(a, b):
        dot += x * y
        na += x * x
        nb += y * y
    denom = math.sqrt(na) * math.sqrt(nb)
    if denom <= 0.0:
        return 0.0
    return dot / denom


# ── dedup ──────────────────────────────────────────────────────────────


async def _run_dedup(
    *,
    client_id: str,
    project_id: str,
    proposed_by: str,
    title_vec: list[float] | None,
    desc_vec: list[float] | None,
) -> DedupDecision:
    """3-tier decision:

    - same scope (matching project_id, or both empty) cosine over
      threshold → REJECT
    - same client different project cosine over threshold →
      SUGGEST_CONSOLIDATE
    - otherwise → ALLOW

    Empty embeddings ⇒ ALLOW (caller logs the embed-unavailable warning).
    """
    if not title_vec or not desc_vec:
        return DedupDecision(decision="ALLOW")

    # Pull recent in-flight (DRAFT / AWAITING_APPROVAL) proposals from
    # this proposed_by. We pass project_id="" so the server returns all
    # projects under the client; the local sweep below picks the right
    # decision tier.
    ctx = _server_ctx()
    try:
        resp = await server_task_proposal_stub().ListPendingProposalsForDedup(
            task_proposal_pb2.DedupRequest(
                ctx=ctx,
                client_id=client_id or "",
                project_id="",
                proposed_by=proposed_by,
            ),
        )
    except grpc.RpcError as e:
        logger.warning("DEDUP_UNAVAILABLE dedup lookup failed: %s", e)
        return DedupDecision(decision="ALLOW")
    if not resp.ok:
        logger.warning("DEDUP_UNAVAILABLE server returned not-ok: %s", resp.error)
        return DedupDecision(decision="ALLOW")

    best_same_scope = DedupDecision(decision="ALLOW")
    best_other_project = DedupDecision(decision="ALLOW")
    for cand in resp.candidates:
        c_title = list(cand.title_embedding)
        c_desc = list(cand.description_embedding)
        if not c_title or not c_desc:
            continue
        ts = _cosine(title_vec, c_title)
        ds = _cosine(desc_vec, c_desc)
        if ts < TITLE_SIM_REJECT or ds < DESC_SIM_REJECT:
            continue
        cand_pid = cand.project_id or ""
        if cand_pid == (project_id or ""):
            # Same scope — strongest existing match wins.
            if ts + ds > best_same_scope.title_sim + best_same_scope.desc_sim:
                best_same_scope = DedupDecision(
                    decision="REJECT",
                    conflicting_task_id=cand.task_id,
                    conflicting_title=cand.title,
                    title_sim=ts,
                    desc_sim=ds,
                )
        else:
            if ts + ds > best_other_project.title_sim + best_other_project.desc_sim:
                best_other_project = DedupDecision(
                    decision="SUGGEST_CONSOLIDATE",
                    conflicting_task_id=cand.task_id,
                    conflicting_title=cand.title,
                    title_sim=ts,
                    desc_sim=ds,
                )

    if best_same_scope.decision == "REJECT":
        return best_same_scope
    if best_other_project.decision == "SUGGEST_CONSOLIDATE":
        return best_other_project
    return DedupDecision(decision="ALLOW")


# ── public API ─────────────────────────────────────────────────────────


async def propose_task(
    *,
    client_id: str,
    project_id: str,
    title: str,
    description: str,
    reason: str,
    proposed_by: str,
    proposal_task_type: str,
    scheduled_at_iso: str = "",
    parent_task_id: str = "",
    depends_on_task_ids: list[str] | None = None,
) -> ProposeResult:
    """Embed → dedup → InsertProposal."""
    if not client_id:
        return ProposeResult(ok=False, error="client_id required")
    if not title.strip():
        return ProposeResult(ok=False, error="title required")
    if not description.strip():
        return ProposeResult(ok=False, error="description required")
    if not proposed_by:
        return ProposeResult(ok=False, error="proposed_by required")
    if not proposal_task_type:
        return ProposeResult(ok=False, error="proposal_task_type required")

    title_vec, desc_vec = await _embed_pair(
        title=title,
        description=description,
        client_id=client_id,
    )

    decision = await _run_dedup(
        client_id=client_id,
        project_id=project_id,
        proposed_by=proposed_by,
        title_vec=title_vec,
        desc_vec=desc_vec,
    )
    if decision.decision == "REJECT":
        return ProposeResult(
            ok=False,
            error=(
                f"REJECT: near-duplicate proposal already exists "
                f"(task_id={decision.conflicting_task_id}, "
                f"title='{decision.conflicting_title}', "
                f"title_sim={decision.title_sim:.2f}, "
                f"desc_sim={decision.desc_sim:.2f}). "
                "Use update_proposed_task to refine that one, or send_for_approval if it's done."
            ),
            decision="REJECT",
            conflicting_task_id=decision.conflicting_task_id,
            conflicting_title=decision.conflicting_title,
        )
    if decision.decision == "SUGGEST_CONSOLIDATE":
        # Soft signal — return ok=False with the hint so the MCP tool
        # surfaces the conflict to Claude. Claude can decide to update
        # the existing task or insist on a fresh one (caller would call
        # again with a clearer scope).
        return ProposeResult(
            ok=False,
            error=(
                f"SUGGEST_CONSOLIDATE: similar proposal in another project "
                f"(task_id={decision.conflicting_task_id}, "
                f"title='{decision.conflicting_title}', "
                f"title_sim={decision.title_sim:.2f}, "
                f"desc_sim={decision.desc_sim:.2f}). "
                "Consider updating that one or scoping this proposal more narrowly."
            ),
            decision="SUGGEST_CONSOLIDATE",
            conflicting_task_id=decision.conflicting_task_id,
            conflicting_title=decision.conflicting_title,
        )

    # ALLOW → forward to Kotlin.
    ctx = _server_ctx()
    try:
        resp = await server_task_proposal_stub().InsertProposal(
            task_proposal_pb2.InsertProposalRequest(
                ctx=ctx,
                client_id=client_id,
                project_id=project_id or "",
                title=title,
                description=description,
                reason=reason or "",
                proposed_by=proposed_by,
                proposal_task_type=proposal_task_type.upper(),
                scheduled_at_iso=scheduled_at_iso or "",
                parent_task_id=parent_task_id or "",
                depends_on_task_ids=list(depends_on_task_ids or []),
                title_embedding=list(title_vec or []),
                description_embedding=list(desc_vec or []),
            ),
        )
    except grpc.RpcError as e:
        return ProposeResult(ok=False, error=f"InsertProposal RPC failed: {e}")
    if not resp.ok:
        return ProposeResult(ok=False, error=resp.error)
    return ProposeResult(ok=True, task_id=resp.task_id)


async def update_proposed_task(
    *,
    task_id: str,
    title: str = "",
    description: str = "",
    reason: str = "",
    proposal_task_type: str = "",
    scheduled_at_iso: str = "",
    client_id_for_embed: str = "",
) -> ProposeResult:
    """Forward to Kotlin UpdateProposal, re-embedding title/description
    only if they were actually edited."""
    if not task_id:
        return ProposeResult(ok=False, error="task_id required")

    title_vec: list[float] | None = None
    desc_vec: list[float] | None = None
    if title.strip() or description.strip():
        title_vec, desc_vec = await _embed_pair(
            title=title,
            description=description,
            client_id=client_id_for_embed,
        )

    ctx = _server_ctx()
    try:
        resp = await server_task_proposal_stub().UpdateProposal(
            task_proposal_pb2.UpdateProposalRequest(
                ctx=ctx,
                task_id=task_id,
                title=title or "",
                description=description or "",
                reason=reason or "",
                proposal_task_type=proposal_task_type.upper() if proposal_task_type else "",
                scheduled_at_iso=scheduled_at_iso or "",
                title_embedding=list(title_vec or []),
                description_embedding=list(desc_vec or []),
            ),
        )
    except grpc.RpcError as e:
        return ProposeResult(ok=False, error=f"UpdateProposal RPC failed: {e}")
    if not resp.ok:
        return ProposeResult(ok=False, error=resp.error)
    return ProposeResult(ok=True, task_id=task_id)


async def send_for_approval(*, task_id: str) -> ProposeResult:
    if not task_id:
        return ProposeResult(ok=False, error="task_id required")
    ctx = _server_ctx()
    try:
        resp = await server_task_proposal_stub().SendForApproval(
            task_proposal_pb2.TaskIdRequest(ctx=ctx, task_id=task_id),
        )
    except grpc.RpcError as e:
        return ProposeResult(ok=False, error=f"SendForApproval RPC failed: {e}")
    if not resp.ok:
        return ProposeResult(ok=False, error=resp.error)
    return ProposeResult(ok=True, task_id=task_id)
