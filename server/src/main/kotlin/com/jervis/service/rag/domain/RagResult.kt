package com.jervis.service.rag.domain

/**
 * Represents the final result of RAG pipeline execution.
 */
data class RagResult(
    val answer: String,
    val queriesProcessed: Int,
    val totalChunksFound: Int,
    val totalChunksFiltered: Int,
)
