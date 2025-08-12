package com.jervis.service.llm.ollama

data class OllamaModel(
    val name: String,
    val size: Long,
    val modified: String,
)
