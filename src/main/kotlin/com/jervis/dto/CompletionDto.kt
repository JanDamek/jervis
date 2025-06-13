package com.jervis.dto

// Base class for completion requests
open class BaseCompletionRequest(
    open val model: String? = null,
    open val temperature: Double? = null,
    open val max_tokens: Int? = null,
    open val stream: Boolean? = null,
)

data class CompletionRequest(
    override val model: String? = null,
    val prompt: String = "",
    override val temperature: Double? = null,
    override val max_tokens: Int? = null,
    override val stream: Boolean? = null,
) : BaseCompletionRequest(model, temperature, max_tokens, stream)

data class ChatCompletionRequest(
    override val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    override val temperature: Double? = null,
    override val max_tokens: Int? = null,
    override val stream: Boolean? = null,
) : BaseCompletionRequest(model, temperature, max_tokens, stream)

data class ChatMessage(
    val role: String = "",
    val content: String = "",
)

// Base class for choices in completion responses
open class BaseChoice(
    open val index: Int,
    open val finish_reason: String?
)

data class CompletionChoice(
    val text: String,
    override val index: Int,
    val logprobs: Any? = null,
    override val finish_reason: String = "stop",
) : BaseChoice(index, finish_reason)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int,
)

// Base class for completion responses
open class BaseCompletionResponse(
    open val id: String,
    open val `object`: String,
    open val created: Long,
    open val model: String,
    open val usage: Usage?
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
    override val usage: Usage?
) : BaseCompletionResponse(id, `object`, created, model, usage)

data class Choice(
    override val index: Int,
    val message: ChatMessage,
    override val finish_reason: String?
) : BaseChoice(index, finish_reason)

// Classes for streaming responses
data class ChatCompletionChunk(
    override val id: String,
    override val `object`: String,
    override val created: Long,
    override val model: String,
    val choices: List<ChunkChoice>,
    override val usage: Usage? = null
) : BaseCompletionResponse(id, `object`, created, model, usage)

data class ChunkChoice(
    override val index: Int,
    val delta: DeltaMessage,
    override val finish_reason: String? = null
) : BaseChoice(index, finish_reason)

data class DeltaMessage(
    val role: String? = null,
    val content: String? = null
)

data class EmbeddingRequest(
    val model: String? = null,
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
    val owned_by: String = "user",
)
