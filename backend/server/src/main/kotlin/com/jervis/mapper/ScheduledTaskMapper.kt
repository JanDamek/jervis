package com.jervis.mapper

import com.jervis.dto.ScheduledTaskDto
import com.jervis.entity.ScheduledTaskDocument
import org.bson.types.ObjectId
import java.time.Instant

fun ScheduledTaskDocument.toDto(): ScheduledTaskDto =
    ScheduledTaskDto(
        id = this.id.toString(),
        clientId = this.clientId.toString(),
        projectId = this.projectId?.toString(),
        content = this.content,
        taskName = this.taskName,
        scheduledAt = this.scheduledAt.toEpochMilli(),
        cronExpression = this.cronExpression,
        correlationId = this.correlationId,
    )
