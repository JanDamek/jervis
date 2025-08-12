package com.jervis.service.llm.anthropic

import com.fasterxml.jackson.annotation.JsonProperty
import com.jervis.domain.llm.Message

/**
 * Anthropic API request
 */
data class AnthropicRequest(
    val model: String,
    val system: String,
    val messages: List<Message>,
    @JsonProperty("max_tokens")
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
) {
    init {
        require(maxTokens >= 1) { "max_tokens must be greater than or equal to 1" }
    }
}
