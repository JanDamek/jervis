package com.jervis.task

/**
 * Lifecycle state of a Claude-proposed task. Distinct from
 * [com.jervis.dto.task.TaskStateEnum] (NEW/INDEXING/QUEUED/PROCESSING/…),
 * which tracks pipeline progress, while ProposalStage tracks the
 * approval-flow dimension orthogonal to it.
 *
 *  - [DRAFT]              — Claude session can freely call
 *                           `update_proposed_task`; BackgroundEngine SKIPs.
 *  - [AWAITING_APPROVAL]  — Immutable. Update tools return INVALID_STATE.
 *                           Surfaced in UserTasksScreen with approve/reject.
 *  - [APPROVED]           — User approved; together with state=NEW makes
 *                           the task eligible for BackgroundEngine pickup,
 *                           which then dispatches via the handler matched
 *                           to [ProposalTaskType].
 *  - [REJECTED]           — User rejected; `proposalRejectionReason`
 *                           carries the explanation. Claude can re-propose
 *                           after `update_proposed_task` (REJECTED is mutable).
 *
 * `proposalStage = null` means a legacy / user-created task with no
 * proposal flow — always pickup-eligible (BackgroundEngine treats null as
 * APPROVED for routing purposes).
 */
enum class ProposalStage {
    DRAFT,
    AWAITING_APPROVAL,
    APPROVED,
    REJECTED,
}

/**
 * Execution-handler dimension for an approved proposed task.
 *
 * Distinct from [com.jervis.dto.task.TaskTypeEnum], which describes the
 * *processing pipeline* (INSTANT/SCHEDULED/SYSTEM). ProposalTaskType tells
 * BackgroundEngine which side-effect handler to dispatch once the user
 * approves the proposal:
 *
 *  - [CODING]             — `dispatch_agent_job` (existing K8s Job path)
 *  - [MAIL_REPLY]         — `o365_mail_send` from task description body
 *  - [TEAMS_REPLY]        — Teams pod send
 *  - [CALENDAR_RESPONSE]  — o365 calendar accept/decline
 *  - [BUGTRACKER_ENTRY]   — `create_issue` MCP backend
 *  - [MEETING_ATTEND]     — existing meeting attend flow (audio routing,
 *                           transcription start)
 *  - [OTHER]              — manual review (state → USER_TASK)
 */
enum class ProposalTaskType {
    CODING,
    MAIL_REPLY,
    TEAMS_REPLY,
    CALENDAR_RESPONSE,
    BUGTRACKER_ENTRY,
    MEETING_ATTEND,
    OTHER,
}
