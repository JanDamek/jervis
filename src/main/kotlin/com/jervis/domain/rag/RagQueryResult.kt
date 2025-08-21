package com.jervis.domain.rag

/**
 * Result of a RAG (Retrieval-Augmented Generation) query.
 * This is a minimal model to allow controllers to compile.
 */
data class RagQueryResult(
    val answer: String,
    val finishReason: String = "stop",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)
