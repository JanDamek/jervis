package com.jervis.task

import com.jervis.dto.task.ScheduledTaskDto
import com.jervis.task.TaskDocument
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
        state = this.state,
    )
