package com.jervis.service.rag.domain

import kotlinx.serialization.Serializable

/**
 * Represents a single RAG query with parameters.
 *
 * Note on similarity threshold:
 * - Vector similarity scores typically range 0.0-1.0
 * - Actual score ranges depend on embedding model and data characteristics
 * - For semantic search, scores 0.15-0.30 often contain relevant results
 * - Default 0.15 provides inclusive retrieval, relying on LLM synthesis for final filtering
 * - Higher thresholds (0.25+) may filter out valid but less exact matches
 */
@Serializable
data class RagQuery(
    val searchTerms: String = "",
    val maxChunks: Int = 5,
    val minSimilarityThreshold: Double = 0.15,
)
