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
    /** USER role only — when the user submitted this turn (ISO-8601 string, empty if N/A). */
    val requestTime: String = "",
    /** Non-USER roles — when the response landed (ISO-8601 string, empty if N/A). */
    val responseTime: String = "",
    val correlationId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val sequence: Long? = null,
    val messageId: String? = null, // MongoDB ObjectId for pagination cursor
    val isOutOfScope: Boolean = false, // Server sets true when message scope doesn't match current filter
    val isDecomposed: Boolean = false, // Master request was decomposed into sub-requests
    val parentRequestId: String? = null, // For sub-requests: ID of the master request
) {
    /**
     * Convenience accessor for sort / display — picks `responseTime`
     * (non-USER) and falls back to `requestTime` (USER). Returns `""`
     * only if both are empty (never under normal flow).
     */
    val effectiveTimestamp: String
        get() = responseTime.ifEmpty { requestTime }
}
