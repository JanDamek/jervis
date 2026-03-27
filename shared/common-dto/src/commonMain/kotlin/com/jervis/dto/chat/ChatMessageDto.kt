package com.jervis.dto.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ChatRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("system") SYSTEM,
    @SerialName("background") BACKGROUND,
    @SerialName("alert") ALERT,
}

@Serializable
data class ChatMessageDto(
    val role: ChatRole = ChatRole.USER,
    val content: String = "",
    val timestamp: String = "", // ISO-8601 format
    val correlationId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val sequence: Long? = null,
    val messageId: String? = null, // MongoDB ObjectId for pagination cursor
    val isOutOfScope: Boolean = false, // Server sets true when message scope doesn't match current filter
    val isDecomposed: Boolean = false, // Master request was decomposed into sub-requests
    val parentRequestId: String? = null, // For sub-requests: ID of the master request
)
