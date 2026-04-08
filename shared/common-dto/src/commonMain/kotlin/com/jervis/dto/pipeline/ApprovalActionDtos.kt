package com.jervis.dto.pipeline

import kotlinx.serialization.Serializable

/**
 * EPIC 4/5: Approval action types for the universal approval gate.
 * Every write action passes through the approval gate before execution.
 */
@Serializable
enum class ApprovalAction {
    GIT_COMMIT,
    GIT_PUSH,
    GIT_CREATE_BRANCH,
    EMAIL_SEND,
    EMAIL_REPLY,
    PR_CREATE,
    PR_COMMENT,
    PR_MERGE,
    CHAT_REPLY,
    KB_DELETE,
    KB_STORE,
    DEPLOY,
    CODING_DISPATCH,

    /**
     * Permission to passively attend (record + transcribe) a single online
     * meeting. Always per individual occurrence — never granted for a recurring
     * series. First-version is read-only: no messages, no audio out, no replies.
     */
    MEETING_ATTEND,
}

/**
 * Result of approval gate evaluation.
 */
@Serializable
enum class ApprovalDecision {
    /** Action approved automatically by guidelines rules. */
    AUTO_APPROVED,

    /** Action requires explicit user approval (interrupt). */
    NEEDS_APPROVAL,

    /** Action denied by guidelines rules. */
    DENIED,
}

/**
 * Request to execute an approved action.
 */
@Serializable
data class ActionExecutionRequest(
    val action: ApprovalAction,
    val payload: Map<String, String> = emptyMap(),
    val clientId: String,
    val projectId: String? = null,
    val correlationId: String? = null,
    val approvalContext: String? = null,
)

/**
 * Result of action execution.
 */
@Serializable
data class ActionExecutionResult(
    val success: Boolean,
    val message: String,
    val action: ApprovalAction,
    val artifactId: String? = null,
)

/**
 * Enhanced USER_TASK fields for approval queue.
 */
@Serializable
data class ApprovalQueueItem(
    val taskId: String,
    val action: ApprovalAction,
    val preview: String,
    val context: String,
    val riskLevel: String = "MEDIUM",
    val payload: Map<String, String> = emptyMap(),
)
