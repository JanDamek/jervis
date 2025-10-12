package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * Represents the response sent back to the UI.
 */
@Serializable
data class ChatResponse(
    val message: String,
)
