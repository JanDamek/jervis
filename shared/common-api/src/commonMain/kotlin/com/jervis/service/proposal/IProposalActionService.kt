package com.jervis.service.proposal

import com.jervis.dto.proposal.ProposalActionResultDto
import com.jervis.dto.proposal.UpdateProposalRequestDto
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * UI-facing kRPC service for the Claude CLI proposal lifecycle (PR4).
 *
 * Wraps the server-internal gRPC `ServerTaskProposalGrpcImpl` (which is
 * the surface the orchestrator uses for InsertProposal / dedup). The
 * UI's approve/reject path goes through this kRPC service instead.
 *
 * Push-only count surface: [subscribePendingProposalsCount] is a
 * `Flow<Int>` with `replay=1` so the sidebar badge "Návrhy ke schválení
 * (N)" is always live without UI-side polling (rule #9).
 */
@Rpc
interface IProposalActionService {
    /**
     * Approve a proposal in `AWAITING_APPROVAL` stage. Atomic findAndModify
     * sets `proposalStage=APPROVED` + `state=QUEUED` so BackgroundEngine
     * picks it up on the next tick (no NEW/INDEXING detour — proposal
     * already carries title + description + scope).
     *
     * Returns `ok=false / error="INVALID_STATE: …"` when the proposal
     * isn't in AWAITING_APPROVAL.
     */
    suspend fun approveProposal(taskId: String): ProposalActionResultDto

    /**
     * Reject a proposal in `AWAITING_APPROVAL`. Stores `reason` (min 5
     * chars enforced by UI) into `proposalRejectionReason` so the
     * Claude session can see the feedback and re-propose.
     */
    suspend fun rejectProposal(taskId: String, reason: String): ProposalActionResultDto

    /**
     * Update a proposal (DRAFT or REJECTED only — AWAITING_APPROVAL /
     * APPROVED are immutable). Mirrors `ServerTaskProposalGrpcImpl
     * .updateProposal`. Update on REJECTED automatically transitions
     * the proposal back to DRAFT and clears `proposalRejectionReason`.
     */
    suspend fun updateProposal(
        taskId: String,
        request: UpdateProposalRequestDto,
    ): ProposalActionResultDto

    /**
     * PR-Q5 — Submit a DRAFT proposal for user approval (DRAFT →
     * AWAITING_APPROVAL). Returns `ok=false / error="INVALID_STATE: …"`
     * when the proposal isn't in DRAFT (e.g. already AWAITING_APPROVAL,
     * APPROVED, or REJECTED). Mirrors
     * `ServerTaskProposalGrpcImpl.sendForApproval`.
     */
    suspend fun sendForApproval(taskId: String): ProposalActionResultDto

    /**
     * Push stream — current count of proposals in stage
     * AWAITING_APPROVAL across all clients. First emission is the
     * latest snapshot (replay=1); subsequent emissions on every
     * recompute tick. UI sidebar binds the value to "Návrhy ke
     * schválení (N)".
     */
    fun subscribePendingProposalsCount(): Flow<Int>
}
