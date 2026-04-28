package com.jervis.dto.proposal

import kotlinx.serialization.Serializable

/**
 * Result of approve/reject/update on a Claude-proposed task.
 *
 * @property ok Whether the underlying transition succeeded. Server-side
 *   findAndModify uses stage predicates so callers see `ok=false` (with
 *   `error="INVALID_STATE: …"`) rather than a silent overwrite when the
 *   proposal is not in the expected stage.
 * @property error Human-readable error string when [ok] is false.
 * @property newStage Resulting [proposalStage] enum name when [ok] is
 *   true (`APPROVED` / `REJECTED` / `DRAFT`).
 */
@Serializable
data class ProposalActionResultDto(
    val ok: Boolean,
    val error: String? = null,
    val newStage: String? = null,
)

/**
 * Patch payload for `IProposalActionService.updateProposal`. All fields
 * optional — only non-null values are applied. Server-side mirrors
 * `ServerTaskProposalGrpcImpl.updateProposal` semantics: REJECTED and
 * DRAFT proposals are mutable; AWAITING_APPROVAL/APPROVED return
 * `INVALID_STATE`.
 */
@Serializable
data class UpdateProposalRequestDto(
    val title: String? = null,
    val description: String? = null,
    val reason: String? = null,
    val scheduledAtIso: String? = null,
    val proposalTaskType: String? = null,
)
