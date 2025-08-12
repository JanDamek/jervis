package com.jervis.domain.llm

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Data class for choice in chat completion response.
 */
data class Choice(
    val index: Int,
    val message: Message,
    @JsonProperty("finish_reason")
    val finishReason: String,
)
