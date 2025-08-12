package com.jervis.domain.llm

/**
 * Data class for chat completion response.
 */
data class ChatCompletionResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage,
)
