package com.jervis.task

import com.jervis.dto.task.PendingTaskDto
import com.jervis.dto.user.UserTaskDto
import com.jervis.dto.user.UserTaskListItemDto
import com.jervis.chat.toDto
import com.jervis.task.TaskDocument

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
        pendingQuestionPreview = this.pendingUserQuestion?.take(120),
        parentTaskId = this.parentTaskId?.toString(),
        childCount = childCount,
        completedChildCount = completedChildCount,
        phase = this.phase,
        priorityScore = this.priorityScore,
    )

fun TaskDocument.toPendingTaskDto(
    childCount: Int = 0,
    completedChildCount: Int = 0,
): PendingTaskDto =
    PendingTaskDto(
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
        needsQualification = this.needsQualification,
    )
