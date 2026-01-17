package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * Error notification sent to UI clients via Flow.
 */
@Serializable
data class ErrorNotificationDto(
    val id: String,
    val timestamp: String,
    val severity: String, // "ERROR", "WARNING", "INFO"
    val message: String,
    val stackTrace: String? = null,
    val clientId: String? = null,
    val projectId: String? = null,
    val correlationId: String? = null,
)
