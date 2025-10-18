package com.jervis.mapper

import com.jervis.dto.ScheduledTaskDto
import com.jervis.entity.mongo.ScheduledTaskDocument
import org.bson.types.ObjectId
import java.time.Instant

fun ScheduledTaskDocument.toDto(): ScheduledTaskDto =
    ScheduledTaskDto(
        id = this.id.toHexString(),
        projectId = this.projectId.toHexString(),
        taskInstruction = this.taskInstruction,
        status = this.status,
        taskName = this.taskName,
        taskParameters = this.taskParameters,
        scheduledAt = this.scheduledAt.toEpochMilli(),
        startedAt = this.startedAt?.toEpochMilli(),
        completedAt = this.completedAt?.toEpochMilli(),
        errorMessage = this.errorMessage,
        retryCount = this.retryCount,
        maxRetries = this.maxRetries,
        priority = this.priority,
        cronExpression = this.cronExpression,
        createdBy = this.createdBy,
    )

fun ScheduledTaskDto.toDocument(): ScheduledTaskDocument =
    ScheduledTaskDocument(
        id = ObjectId(this.id),
        projectId = ObjectId(this.projectId),
        taskInstruction = this.taskInstruction,
        status = this.status,
        taskName = this.taskName,
        taskParameters = this.taskParameters,
        scheduledAt = Instant.ofEpochMilli(this.scheduledAt),
        startedAt = this.startedAt?.let { Instant.ofEpochMilli(it) },
        completedAt = this.completedAt?.let { Instant.ofEpochMilli(it) },
        errorMessage = this.errorMessage,
        retryCount = this.retryCount,
        maxRetries = this.maxRetries,
        priority = this.priority,
        cronExpression = this.cronExpression,
        createdBy = this.createdBy,
    )
