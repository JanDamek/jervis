package com.jervis.service.llm.ollama

data class OllamaResponse(
    val model: String,
    val response: String,
    val promptEvalCount: Int,
    val evalCount: Int,
)
