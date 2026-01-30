package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatSessionDto(
    val sessionId: String,
    val clientId: String,
    val projectId: String? = null,
    val messages: List<ChatMessageDto> = emptyList(),
    val checkpointId: String? = null,
    val lastCorrelationId: String? = null,
    val createdAt: String,
    val lastActivityAt: String,
    val metadata: Map<String, String> = emptyMap(),
)
