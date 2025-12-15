package com.jervis.dto

import com.jervis.types.ClientId
import com.jervis.types.ProjectId

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
    val clientId: ClientId,
    val projectId: ProjectId? = null,
)
