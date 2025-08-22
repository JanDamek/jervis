package com.jervis.dto

/**
 * Represents the response sent back to the UI.
 */
data class ChatResponse(
    val message: String,
    val detectedClient: String? = null,
    val detectedProject: String? = null,
    val englishText: String? = null,
)
