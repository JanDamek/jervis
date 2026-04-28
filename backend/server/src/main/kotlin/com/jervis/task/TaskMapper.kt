package com.jervis.task

import com.jervis.dto.task.PendingTaskDto
import com.jervis.dto.task.TaskProposalInfoDto
import com.jervis.dto.user.UserTaskDto
import com.jervis.dto.user.UserTaskListItemDto
import com.jervis.chat.toDto
import com.jervis.task.TaskDocument

/**
 * Build the proposal-lifecycle DTO from a TaskDocument. Returns `null`
 * for legacy/user-created tasks (proposalStage == null), which is the
 * UI's "no badge / no approval flow" signal.
 */
internal fun TaskDocument.toProposalInfoDto(): TaskProposalInfoDto? {
    val stage = this.proposalStage ?: return null
    return TaskProposalInfoDto(
        proposedBy = this.proposedBy.orEmpty(),
        proposalReason = this.proposalReason.orEmpty(),
        proposalStage = stage.name,
        proposalRejectionReason = this.proposalRejectionReason,
        proposalTaskType = this.proposalTaskType?.name,
    )
}

fun TaskDocument.toUserTaskDto(): UserTaskDto =
    UserTaskDto(
        id = this.id.toString(),
        title = this.taskName,
        description = this.content,
        state = this.state.name,
        projectId = this.projectId?.toString(),
        clientId = this.clientId.toString(),
        sourceUri = this.correlationId,
        createdAtEpochMillis = this.createdAt.toEpochMilli(),
        attachments = this.attachments.map { it.toDto() },
        pendingQuestion = this.pendingUserQuestion,
        questionContext = this.userQuestionContext,
        priorityScore = this.priorityScore,
        proposalInfo = this.toProposalInfoDto(),
    )

/** Lightweight mapper for list view — skips content, attachments, agentCheckpointJson. */
fun TaskDocument.toUserTaskListItemDto(
    childCount: Int = 0,
    completedChildCount: Int = 0,
): UserTaskListItemDto =
    UserTaskListItemDto(
        id = this.id.toString(),
        title = this.taskName,
        state = this.state.name,
        projectId = this.projectId?.toString(),
        clientId = this.clientId.toString(),
        createdAtEpochMillis = this.createdAt.toEpochMilli(),
        hasPendingQuestion = !this.pendingUserQuestion.isNullOrBlank(),
        pendingQuestionPreview = this.pendingUserQuestion,
        parentTaskId = this.parentTaskId?.toString(),
        childCount = childCount,
        completedChildCount = completedChildCount,
        phase = this.phase,
        priorityScore = this.priorityScore,
        proposalInfo = this.toProposalInfoDto(),
    )

fun TaskDocument.toPendingTaskDto(
    childCount: Int = 0,
    completedChildCount: Int = 0,
): PendingTaskDto {
    // qualifierPreparedContext is a JSON blob the qualifier dumps. The Kotlin
    // /internal/qualification-done callback writes it as
    // "${contextSummary}\n\n${suggestedApproach}". We split on the first \n\n
    // so the UI can render the two halves under separate headings — without
    // changing the qualifier's wire format.
    val rawCtx = this.qualifierPreparedContext
    val (ctxSummary, ctxApproach) = if (rawCtx.isNullOrBlank()) {
        null to null
    } else {
        val parts = rawCtx.split("\n\n", limit = 2)
        (parts.getOrNull(0)?.takeIf { it.isNotBlank() }) to
            (parts.getOrNull(1)?.takeIf { it.isNotBlank() })
    }
    val lastStep = this.qualificationSteps.lastOrNull()?.message?.takeIf { it.isNotBlank() }

    return PendingTaskDto(
        id = this.id.toString(),
        taskType = this.type.name,
        content = this.content,
        projectId = this.projectId?.toString(),
        clientId = this.clientId.toString(),
        createdAt = this.createdAt.toString(),
        state = this.state.name,
        attachments = this.attachments.map { it.toDto() },
        parentTaskId = this.parentTaskId?.toString(),
        childCount = childCount,
        completedChildCount = completedChildCount,
        phase = this.phase,
        taskName = this.taskName,
        sourceLabel = this.sourceUrn.uiLabel(),
        sourceScheme = this.sourceUrn.scheme(),
        pendingUserQuestion = this.pendingUserQuestion,
        userQuestionContext = this.userQuestionContext,
        needsQualification = this.needsQualification,
        kbSummary = this.kbSummary,
        kbEntities = this.kbEntities,
        priorityScore = this.priorityScore,
        priorityReason = this.priorityReason,
        actionType = this.actionType,
        estimatedComplexity = this.estimatedComplexity,
        qualifierContextSummary = ctxSummary,
        qualifierSuggestedApproach = ctxApproach,
        lastQualificationStep = lastStep,
        summary = this.summary,
        deadlineIso = this.deadline?.toString(),
        userPresence = this.userPresence,
        proposalInfo = this.toProposalInfoDto(),
    )
}
