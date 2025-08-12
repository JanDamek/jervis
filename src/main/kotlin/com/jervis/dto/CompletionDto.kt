package com.jervis.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class ChatMessage(
    val role: String = "",
    val content: String = "",
)

// Base class for choices in completion responses
open class BaseChoice(
    open val index: Int,
    @JsonProperty("finish_reason")
    open val finishReason: String?,
)

data class Usage(
    @JsonProperty("prompt_tokens")
    val promptTokens: Int,
    @JsonProperty("completion_tokens")
    val completionTokens: Int,
    @JsonProperty("total_tokens")
    val totalTokens: Int,
)

data class Choice(
    override val index: Int,
    val message: ChatMessage,
    @JsonProperty("finish_reason")
    override val finishReason: String?,
) : BaseChoice(index, finishReason)

data class ChunkChoice(
    override val index: Int,
    val delta: DeltaMessage,
    @JsonProperty("finish_reason")
    override val finishReason: String? = null,
) : BaseChoice(index, finishReason)

data class DeltaMessage(
    val role: String? = null,
    val content: String? = null,
)

data class ModelData(
    val id: String,
    val `object`: String = "model",
    @JsonProperty("owned_by")
    val ownedBy: String = "user",
)
