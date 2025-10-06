package com.jervis.service.rag.domain

import kotlinx.serialization.Serializable

/**
 * Represents a single document chunk retrieved from vector storage.
 */
@Serializable
data class DocumentChunk(
    val content: String,
    val score: Double,
    val metadata: Map<String, String>,
    val embedding: List<Float> = emptyList(),
)

/**
 * Represents a single RAG query with parameters.
 */
@Serializable
data class RagQuery(
    val searchTerms: String = "",
    val scoreThreshold: Float = 0.7f,
    val global: Boolean = false,
)

/**
 * Represents the final result of RAG pipeline execution.
 */
data class RagResult(
    val answer: String,
    val queriesProcessed: Int,
    val totalChunksFound: Int,
    val totalChunksFiltered: Int,
)

/**
 * Response from synthesis LLM containing the final answer.
 */
@Serializable
data class AnswerResponse(
    val answer: String = "",
)
