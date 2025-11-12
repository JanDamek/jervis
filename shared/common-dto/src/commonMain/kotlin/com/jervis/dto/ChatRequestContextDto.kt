package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * Provides the context for a user's chat request.
 *
 * The context narrows the scope of the agent by optionally specifying
 * the client and project names. When autoScope is true, the agent may
 * attempt to detect or correct the scope automatically.
 *
 * When quick is true, routing prefers models marked as quick.
 */
@Serializable
data class ChatRequestContextDto(
    val clientId: String,
    val projectId: String,
    val quick: Boolean = false,
)
