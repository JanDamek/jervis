package com.jervis.dto.events

import kotlinx.serialization.Serializable

@Serializable
data class ErrorNotificationEventDto(
    val eventType: String = "ERROR",
    val message: String,
    val stackTrace: String? = null,
    val correlationId: String? = null,
    val timestamp: String,
)
