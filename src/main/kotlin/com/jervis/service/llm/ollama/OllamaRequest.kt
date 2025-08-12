package com.jervis.service.llm.ollama

/**
 * Data classes for Ollama API
 */
data class OllamaRequest(
    val model: String,
    val prompt: String,
    val options: OllamaOptions,
)
