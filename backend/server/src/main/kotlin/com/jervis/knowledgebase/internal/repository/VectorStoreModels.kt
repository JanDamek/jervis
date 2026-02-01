package com.jervis.knowledgebase.internal.repository

import com.jervis.types.ClientId
import com.jervis.types.ProjectId

/**
 * Internal models for vector store operations.
 * Package-private - not exposed outside com.jervis.rag.
 */

/**
 * Vector query for search operations.
 */
internal data class VectorQuery(
    val embedding: List<Float>,
    val limit: Int,
    val minScore: Float,
    val filters: VectorFilters,
)

/**
 * Filters for vector search.
 */
internal data class VectorFilters(
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val kind: String? = null, // "TEXT" | "CODE", null = search both
    val scope: String? = null, // "project" | "client" | "global", null = search all
)

/**
 * Document to store in vector DB.
 */
internal data class VectorDocument(
    val id: String,
    val content: String,
    val embedding: List<Float>,
    val metadata: Map<String, Any>,
)

/**
 * Search result from vector DB.
 */
internal data class VectorSearchResult(
    val id: String,
    val content: String,
    val score: Double,
    val metadata: Map<String, Any>,
)
