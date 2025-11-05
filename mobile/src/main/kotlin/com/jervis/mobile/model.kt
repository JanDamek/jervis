package com.jervis.mobile

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

/** Selected context for requests. */
@Serializable
data class MobileSelection(
    val clientId: String,
    val projectId: String,
)
