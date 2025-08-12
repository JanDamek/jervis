package com.jervis.service.llm.openai

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * OpenAI API request
 */
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @JsonProperty("max_tokens")
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
)
