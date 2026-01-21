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
)

@Serializable
enum class ChatResponseType {
    USER_MESSAGE, // User's message echoed to all clients for synchronization
    PROGRESS,     // Intermediate progress update (e.g., "Analyzing code...", "Searching documentation...")
    FINAL         // Final answer to user
}
