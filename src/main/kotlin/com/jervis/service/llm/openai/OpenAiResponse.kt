package com.jervis.service.llm.openai

/**
 * OpenAI API response
 */
data class OpenAiResponse(
    val id: String,
    val model: String,
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage,
)
