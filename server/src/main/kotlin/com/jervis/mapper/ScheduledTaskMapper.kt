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
        scheduledAt = this.scheduledAt.toString(),
        startedAt = this.startedAt?.toString(),
        completedAt = this.completedAt?.toString(),
        errorMessage = this.errorMessage,
        retryCount = this.retryCount,
        maxRetries = this.maxRetries,
        priority = this.priority,
        cronExpression = this.cronExpression,
        createdAt = this.createdAt.toString(),
        lastUpdatedAt = this.lastUpdatedAt.toString(),
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
        scheduledAt = Instant.parse(this.scheduledAt),
        startedAt = this.startedAt?.let { Instant.parse(it) },
        completedAt = this.completedAt?.let { Instant.parse(it) },
        errorMessage = this.errorMessage,
        retryCount = this.retryCount,
        maxRetries = this.maxRetries,
        priority = this.priority,
        cronExpression = this.cronExpression,
        createdAt = Instant.parse(this.createdAt),
        lastUpdatedAt = Instant.parse(this.lastUpdatedAt),
        createdBy = this.createdBy,
    )
