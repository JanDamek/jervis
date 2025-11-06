package com.jervis.dto.events

import kotlinx.serialization.Serializable

@Serializable
data class AgentResponseEventDto(
    val wsSessionId: String,
    val contextId: String,
    val message: String,
    val status: String, // "STARTED", "PROCESSING", "COMPLETED", "FAILED"
    val timestamp: String,
)
