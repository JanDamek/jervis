package com.jervis.dto

import com.jervis.common.Constants
import com.jervis.domain.task.ScheduledTaskStatusEnum
import kotlinx.serialization.Serializable

@Serializable
data class ScheduledTaskDto(
    val id: String = Constants.GLOBAL_ID_STRING,
    val projectId: String,
    val taskInstruction: String,
    val status: ScheduledTaskStatusEnum = ScheduledTaskStatusEnum.PENDING,
    val taskName: String,
    val taskParameters: Map<String, String> = emptyMap(),
    val scheduledAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val priority: Int = 0,
    val cronExpression: String? = null,
    val createdBy: String = "system",
)
