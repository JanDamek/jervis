package com.jervis.rag

import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn

data class SearchRequest(
    val query: String,
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val maxResults: Int = 20,
    val minScore: Double = 0.15,
)

/**
 * Hybrid search request combining BM25 (keyword) and Vector (semantic) search.
 *
 * @param alpha Balance between BM25 and vector:
 *   - 0.0 = pure BM25 (keyword matching)
 *   - 1.0 = pure vector (semantic similarity)
 *   - 0.5 = balanced hybrid
 */
data class HybridSearchRequest(
    val query: String,
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val maxResults: Int = 20,
    val alpha: Float = 0.5f,
)

@JvmInline
value class SearchResult(
    val text: String,
)

/**
 * Atomic chunk storage request.
 * Agent extracts chunk and provides all metadata - service only embeds and stores.
 */
data class StoreChunkRequest(
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val content: String,
    val sourceUrn: SourceUrn,
    val graphRefs: List<String>,
)
