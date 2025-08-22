package com.jervis.dto

/**
 * Context passed from UI to backend to determine client and project scope for a chat query.
 */
data class ChatRequestContext(
    val clientName: String?,
    val projectName: String?, // null when autoScope is true
    val autoScope: Boolean,
)
