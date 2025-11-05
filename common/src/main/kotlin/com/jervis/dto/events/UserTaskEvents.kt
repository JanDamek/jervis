package com.jervis.dto.events

import kotlinx.serialization.Serializable

@Serializable
data class UserTaskCreatedEventDto(
    val eventType: String = "USER_TASK_CREATED",
    val clientId: String,
    val taskId: String,
    val title: String,
    val timestamp: String,
)
