package com.jervis.dto.task

import kotlinx.serialization.Serializable

/**
 * Claude CLI proposal lifecycle metadata for a task. Mirrors the
 * server-side `proposedBy / proposalReason / proposalStage /
 * proposalRejectionReason / proposalTaskType` fields on TaskDocument.
 *
 * `null` on a parent DTO means the task is a regular user-created task
 * (no proposal flow). Non-null means it originated from a Claude CLI
 * (or qualifier) session and is subject to the approval flow.
 *
 * String fields use enum names ("DRAFT", "AWAITING_APPROVAL",
 * "APPROVED", "REJECTED" for stage; "CODING", "MAIL_REPLY", … for type)
 * so the DTO module does not depend on server enums.
 */
@Serializable
data class TaskProposalInfoDto(
    val proposedBy: String,
    val proposalReason: String,
    val proposalStage: String,
    val proposalRejectionReason: String? = null,
    val proposalTaskType: String? = null,
)
