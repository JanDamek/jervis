package com.jervis.dto.completion

import com.fasterxml.jackson.annotation.JsonProperty
import com.jervis.dto.ChatMessage

data class ChatCompletionRequest(
    override val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    override val temperature: Double? = null,
    @JsonProperty("max_tokens")
    override val maxTokens: Int? = null,
    override val stream: Boolean? = null,
) : BaseCompletionRequest(model, temperature, maxTokens, stream)
