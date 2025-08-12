package com.jervis.service.llm.openai

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Choice from OpenAI API
 */
data class OpenAiChoice(
    val index: Int,
    val message: OpenAiMessage,
    @JsonProperty("finish_reason")
    val finishReason: String,
)
