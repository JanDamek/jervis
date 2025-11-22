package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ScheduleTaskRequestDto(
    val clientId: String,
    val projectId: String?,
    val content: String,
    val taskName: String,
    val scheduledAt: Long,
    val cronExpression: String? = null,
    val correlationId: String? = null,
)
