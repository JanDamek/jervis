package com.jervis.dto

/**
 * Response from ChatService including detected scope and optional English translation.
 */
data class ChatResponse(
    val message: String,
    val detectedClient: String? = null,
    val detectedProject: String? = null,
    val englishText: String? = null,
)
