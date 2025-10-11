package com.jervis.dto.completion

import com.jervis.dto.ChatMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionRequest(
    override val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    override val temperature: Double? = null,
    @SerialName("max_tokens")
    override val maxTokens: Int? = null,
    override val stream: Boolean? = null,
) : BaseCompletionRequest(model, temperature, maxTokens, stream)
