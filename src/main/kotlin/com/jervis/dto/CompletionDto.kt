package com.jervis.dto

import com.fasterxml.jackson.annotation.JsonProperty

// Base class for completion requests
open class BaseCompletionRequest(
    open val model: String? = null,
    open val temperature: Double? = null,
    @JsonProperty("max_tokens")
    open val maxTokens: Int? = null,
    open val stream: Boolean? = null,
)

data class CompletionRequest(
    override val model: String? = null,
    val prompt: String = "",
    override val temperature: Double? = null,
    @JsonProperty("max_tokens")
    override val maxTokens: Int? = null,
    override val stream: Boolean? = null,
) : BaseCompletionRequest(model, temperature, maxTokens, stream)

data class ChatCompletionRequest(
    override val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    override val temperature: Double? = null,
    @JsonProperty("max_tokens")
    override val maxTokens: Int? = null,
    override val stream: Boolean? = null,
) : BaseCompletionRequest(model, temperature, maxTokens, stream)

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

data class CompletionChoice(
    val text: String,
    override val index: Int,
    val logprobs: Any? = null,
    @JsonProperty("finish_reason")
    override val finishReason: String = "stop",
) : BaseChoice(index, finishReason)

data class Usage(
    @JsonProperty("prompt_tokens")
    val promptTokens: Int,
    @JsonProperty("completion_tokens")
    val completionTokens: Int,
    @JsonProperty("total_tokens")
    val totalTokens: Int,
)

// Base class for completion responses
open class BaseCompletionResponse(
    open val id: String,
    open val `object`: String,
    open val created: Long,
    open val model: String,
    open val usage: Usage?,
)

data class CompletionResponse(
    override val id: String,
    override val `object`: String = "text_completion",
    override val created: Long,
    override val model: String,
    val choices: List<CompletionChoice>,
    override val usage: Usage,
) : BaseCompletionResponse(id, `object`, created, model, usage)

data class ChatCompletionResponse(
    override val id: String,
    override val `object`: String,
    override val created: Long,
    override val model: String,
    val choices: List<Choice>,
    override val usage: Usage?,
) : BaseCompletionResponse(id, `object`, created, model, usage)

data class Choice(
    override val index: Int,
    val message: ChatMessage,
    @JsonProperty("finish_reason")
    override val finishReason: String?,
) : BaseChoice(index, finishReason)

// Classes for streaming responses
data class ChatCompletionChunk(
    override val id: String,
    override val `object`: String,
    override val created: Long,
    override val model: String,
    val choices: List<ChunkChoice>,
    override val usage: Usage? = null,
) : BaseCompletionResponse(id, `object`, created, model, usage)

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

data class EmbeddingRequest(
    val model: String,
    val input: List<String> = emptyList(),
)

data class EmbeddingItem(
    val `object`: String = "embedding",
    val embedding: List<Float>,
    val index: Int,
)

data class EmbeddingResponse(
    val data: List<EmbeddingItem>,
    val model: String,
    val usage: Usage,
)

data class ModelData(
    val id: String,
    val `object`: String = "model",
    @JsonProperty("owned_by")
    val ownedBy: String = "user",
)
