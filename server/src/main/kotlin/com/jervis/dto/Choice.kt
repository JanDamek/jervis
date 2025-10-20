package com.jervis.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class Choice(
    val index: Int,
    val message: ChatMessage,
    @JsonProperty("finish_reason")
    val finishReason: String? = null,
)
