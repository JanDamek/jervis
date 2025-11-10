package com.jervis.dto.completion

import com.fasterxml.jackson.annotation.JsonProperty
import com.jervis.dto.ChatMessageDto

data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessageDto> = emptyList(),
    val temperature: Double? = null,
    @JsonProperty("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean? = null,
)
