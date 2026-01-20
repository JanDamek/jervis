package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageDto(
    val role: String = "", // "user", "assistant", "system"
    val content: String = "",
    val timestamp: String = "", // ISO-8601 format
    val correlationId: String? = null,
)
