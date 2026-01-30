package com.jervis.mapper

import com.jervis.dto.PendingTaskDto
import com.jervis.dto.user.UserTaskDto
import com.jervis.entity.TaskDocument

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
        attachments = this.attachments.map { it.toDto() }
    )

fun TaskDocument.toPendingTaskDto(): PendingTaskDto =
    PendingTaskDto(
        id = this.id.toString(),
        taskType = this.type.name,
        content = this.content,
        projectId = this.projectId?.toString(),
        clientId = this.clientId.toString(),
        createdAt = this.createdAt.toString(),
        state = this.state.name,
        attachments = this.attachments.map { it.toDto() }
    )
