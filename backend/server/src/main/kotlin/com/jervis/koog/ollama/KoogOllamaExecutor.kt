package com.jervis.koog.ollama

/**
 * Lightweight descriptor for an Ollama endpoint used by Koog integration.
 * Intentionally does NOT depend on Koog classes to keep compilation stable
 * even if Koog executor APIs change. Actual execution wiring happens elsewhere.
 */
data class KoogOllamaExecutor(
    val name: String,
    val host: String,
)
