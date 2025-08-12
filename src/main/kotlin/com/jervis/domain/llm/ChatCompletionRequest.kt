package com.jervis.domain.llm

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Data class for chat completion request.
 */
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Float = 0.7f,
    @JsonProperty("max_tokens")
    val maxTokens: Int = 1024,
)
