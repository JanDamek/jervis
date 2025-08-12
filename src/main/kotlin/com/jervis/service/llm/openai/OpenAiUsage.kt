package com.jervis.service.llm.openai

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Usage information from OpenAI API
 */
data class OpenAiUsage(
    @JsonProperty("prompt_tokens")
    val promptTokens: Int,
    @JsonProperty("completion_tokens")
    val completionTokens: Int,
    @JsonProperty("total_tokens")
    val totalTokens: Int,
)
