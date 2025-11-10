package com.jervis.dto.events

import kotlinx.serialization.Serializable

@Serializable
data class UserTaskCancelledEventDto(
    val eventType: String = "USER_TASK_CANCELLED",
    val clientId: String,
    val taskId: String,
    val title: String,
    val timestamp: String,
)
