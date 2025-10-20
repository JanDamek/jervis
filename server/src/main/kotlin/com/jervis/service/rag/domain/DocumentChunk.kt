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
