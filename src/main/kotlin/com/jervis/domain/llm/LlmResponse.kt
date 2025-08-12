package com.jervis.domain.llm

/**
 * Response from an LLM
 */
data class LlmResponse(
    val answer: String,
    val model: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val finishReason: String = "stop",
)
