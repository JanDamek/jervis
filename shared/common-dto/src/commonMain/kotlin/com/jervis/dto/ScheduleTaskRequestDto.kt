package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ScheduleTaskRequestDto(
    val projectId: String,
    val taskInstruction: String,
    val taskName: String,
    val scheduledAt: Long,
    val taskParameters: Map<String, String> = emptyMap(),
    val priority: Int = 0,
    val maxRetries: Int = 3,
    val cronExpression: String? = null,
    val createdBy: String = "system",
)
