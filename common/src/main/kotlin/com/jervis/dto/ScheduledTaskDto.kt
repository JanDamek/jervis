package com.jervis.dto

import com.jervis.domain.task.ScheduledTaskStatus
import kotlinx.serialization.Serializable

@Serializable
data class ScheduledTaskDto(
    val id: String,
    val projectId: String,
    val taskInstruction: String,
    val status: ScheduledTaskStatus = ScheduledTaskStatus.PENDING,
    val taskName: String,
    val taskParameters: Map<String, String> = emptyMap(),
    val scheduledAt: String,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val priority: Int = 0,
    val cronExpression: String? = null,
    val createdAt: String,
    val lastUpdatedAt: String,
    val createdBy: String = "system",
)
