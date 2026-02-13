package com.jervis.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ChatRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("system") SYSTEM,
}

@Serializable
data class ChatMessageDto(
    val role: ChatRole = ChatRole.USER,
    val content: String = "",
    val timestamp: String = "", // ISO-8601 format
    val correlationId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val sequence: Long? = null,
)
