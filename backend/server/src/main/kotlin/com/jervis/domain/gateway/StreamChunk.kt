package com.jervis.domain.gateway

/**
 * Represents a chunk of streaming response from an LLM provider
 */
data class StreamChunk(
    val content: String,
    val isComplete: Boolean = false,
    val metadata: Map<String, Any> = emptyMap(),
)
