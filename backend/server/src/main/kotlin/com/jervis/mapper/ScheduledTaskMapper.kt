package com.jervis.mapper

import com.jervis.dto.ScheduledTaskDto
import com.jervis.entity.TaskDocument
import java.time.Instant

fun TaskDocument.toDto(): ScheduledTaskDto =
    ScheduledTaskDto(
        id = this.id.toString(),
        clientId = this.clientId.toString(),
        projectId = this.projectId?.toString(),
        content = this.content,
        taskName = this.taskName,
        scheduledAt = this.scheduledAt?.toEpochMilli() ?: Instant.now().toEpochMilli(),
        cronExpression = this.cronExpression,
        correlationId = this.correlationId,
    )
