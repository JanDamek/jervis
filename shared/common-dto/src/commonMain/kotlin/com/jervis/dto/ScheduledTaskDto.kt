package com.jervis.dto

import com.jervis.common.Constants
import kotlinx.serialization.Serializable

@Serializable
data class ScheduledTaskDto(
    val id: String = Constants.GLOBAL_ID_STRING,
    val clientId: String,
    val projectId: String?,
    val content: String,
    val taskName: String,
    val scheduledAt: Long,
    val cronExpression: String? = null,
    val correlationId: String? = null,
)
