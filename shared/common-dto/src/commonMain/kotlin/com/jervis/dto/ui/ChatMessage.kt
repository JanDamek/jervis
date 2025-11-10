package com.jervis.dto.ui

import kotlinx.serialization.Serializable

/** Simple chat message model for Mobile UIs. */
@Serializable
data class ChatMessage(
    val from: Sender,
    val text: String,
    val contextId: String? = null,
    val timestamp: String? = null,
) {
    @Serializable
    enum class Sender { Me, Assistant }
}
