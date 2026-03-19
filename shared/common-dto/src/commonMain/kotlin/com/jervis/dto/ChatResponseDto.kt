package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * Represents the response sent back to the UI.
 * @property message The message content
 * @property type Message type: PROGRESS (intermediate updates) or FINAL (final answer)
 * @property metadata Additional context like agent name, step description, etc.
 */
@Serializable
data class ChatResponseDto(
    val message: String,
    val type: ChatResponseType = ChatResponseType.FINAL,
    val metadata: Map<String, String> = emptyMap(),
    val messageId: String? = null,
)

@Serializable
enum class ChatResponseType {
    USER_MESSAGE,
    PLANNING,
    EVIDENCE_GATHERING,
    EXECUTING,
    REVIEWING,
    FINAL,
    ERROR,
    CHAT_CHANGED,
    QUEUE_STATUS,
    STREAMING_TOKEN,
    SCOPE_CHANGE,
    APPROVAL_REQUEST,
    BACKGROUND_RESULT,  // Result from a completed background task pushed to chat
    URGENT_ALERT,       // Urgent notification pushed to chat (email, deadline, etc.)
    THINKING_GRAPH_UPDATE,  // Thinking graph (TaskGraph) update from chat planning
}
