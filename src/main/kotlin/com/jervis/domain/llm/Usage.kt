package com.jervis.domain.llm

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Usage information in Anthropic API response
 */
data class Usage(
    @JsonProperty("input_tokens")
    val inputTokens: Int,
    @JsonProperty("output_tokens")
    val outputTokens: Int,
    @JsonProperty("total_tokens")
    val totalTokens: Int,
    @JsonProperty("completion_tokens")
    val completionTokens: Int,
    @JsonProperty("prompt_tokens")
    val promptTokens: Int,
)
