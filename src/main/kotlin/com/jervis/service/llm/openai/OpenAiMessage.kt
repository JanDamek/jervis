package com.jervis.service.llm.openai

/**
 * Message for OpenAI API
 */
data class OpenAiMessage(
    val role: String,
    val content: String,
)
