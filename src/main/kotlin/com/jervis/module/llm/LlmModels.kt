package com.jervis.module.llm

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Data class for chat completion request.
 */
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Float = 0.7f,
    @JsonProperty("max_tokens")
    val maxTokens: Int = 1024
)

/**
 * Data class for message in chat completion request.
 */
data class Message(
    val role: String,
    val content: String
)

/**
 * Data class for chat completion response.
 */
data class ChatCompletionResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage
)

/**
 * Data class for choice in chat completion response.
 */
data class Choice(
    val index: Int,
    val message: Message,
    @JsonProperty("finish_reason")
    val finishReason: String
)

/**
 * Data class for usage information in chat completion response.
 */
data class Usage(
    @JsonProperty("prompt_tokens")
    val promptTokens: Int,
    @JsonProperty("completion_tokens")
    val completionTokens: Int,
    @JsonProperty("total_tokens")
    val totalTokens: Int
)

/**
 * OpenAI API request
 */
data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    @JsonProperty("max_tokens")
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
)

/**
 * Message for OpenAI API
 */
data class OpenAIMessage(
    val role: String,
    val content: String,
)

/**
 * OpenAI API response
 */
data class OpenAIResponse(
    val id: String,
    val model: String,
    val choices: List<OpenAIChoice>,
    val usage: OpenAIUsage,
)

/**
 * Choice from OpenAI API
 */
data class OpenAIChoice(
    val index: Int,
    val message: OpenAIMessage,
    @JsonProperty("finish_reason")
    val finishReason: String,
)

/**
 * Usage information from OpenAI API
 */
data class OpenAIUsage(
    @JsonProperty("prompt_tokens")
    val promptTokens: Int,
    @JsonProperty("completion_tokens")
    val completionTokens: Int,
    @JsonProperty("total_tokens")
    val totalTokens: Int,
)

/**
 * Anthropic API request
 */
data class AnthropicRequest(
    val model: String,
    val system: String,
    val messages: List<Message>,
    @JsonProperty("max_tokens")
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
) {
    init {
        require(maxTokens >= 1) { "max_tokens must be greater than or equal to 1" }
    }
}

/**
 * Anthropic API response
 */
data class AnthropicResponse(
    val id: String,
    val model: String,
    val content: List<ContentBlock>,
    val usage: AnthropicUsage,
)

/**
 * Content block in Anthropic API response
 */
data class ContentBlock(
    val type: String,
    val text: String,
)

/**
 * Usage information in Anthropic API response
 */
data class AnthropicUsage(
    @JsonProperty("input_tokens")
    val inputTokens: Int,
    @JsonProperty("output_tokens")
    val outputTokens: Int,
)
