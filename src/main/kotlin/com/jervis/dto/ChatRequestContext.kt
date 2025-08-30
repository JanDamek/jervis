package com.jervis.dto

/**
 * Provides the context for a user's chat request.
 *
 * The context narrows the scope of the agent by optionally specifying
 * the client and project names. When autoScope is true, the agent may
 * attempt to detect or correct the scope automatically.
 *
 * When quick is true, routing prefers models marked as quick.
 */
data class ChatRequestContext(
    val clientName: String?,
    val projectName: String?,
    val autoScope: Boolean = false,
    val quick: Boolean = false,
    val confirmedScope: Boolean = false,
)
