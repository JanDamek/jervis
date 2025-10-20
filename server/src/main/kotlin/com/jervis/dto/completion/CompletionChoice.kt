package com.jervis.dto.completion

import com.fasterxml.jackson.annotation.JsonProperty

data class CompletionChoice(
    val text: String,
    val index: Int,
    val logprobs: String? = null,
    @JsonProperty("finish_reason")
    val finishReason: String = "stop",
)
