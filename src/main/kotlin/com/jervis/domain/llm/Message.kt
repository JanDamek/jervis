package com.jervis.domain.llm

/**
 * Data class for message in chat completion request.
 */
data class Message(
    val role: String,
    val content: String,
)
