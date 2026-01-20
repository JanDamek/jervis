package com.jervis.dto.ui

import kotlinx.serialization.Serializable

/** Simple chat message model for Mobile UIs. */
@Serializable
data class ChatMessage(
    val from: Sender,
    val text: String,
    val contextId: String? = null,
    val timestamp: String? = null,
    val messageType: MessageType = MessageType.FINAL,
    val metadata: Map<String, String> = emptyMap(),
) {
    @Serializable
    enum class Sender { Me, Assistant }

    @Serializable
    enum class MessageType {
        PROGRESS, // Intermediate progress update
        FINAL     // Final answer
    }
}
