package com.jervis.rag

import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn

import java.time.Instant

/**
 * Request to ingest new knowledge.
 */
data class IngestRequest(
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val sourceUrn: SourceUrn, // canonical identifier including version
    val kind: String, // e.g. "text", "code", "ticket", "log", "meeting"
    val content: String,
    val metadata: Map<String, Any> = emptyMap(),
    val observedAt: Instant = Instant.now(),
)

/**
 * Request to retrieve knowledge for a query.
 */
data class RetrievalRequest(
    val query: String,
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val asOf: Instant? = null, // for historical queries
    val minConfidence: Double = 0.0,
    val maxResults: Int = 10,
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

data class SearchResult(
    val text: String,
    val facts: List<FactSearchResult> = emptyList(),
)

data class FactSearchResult(
    val content: String,
    val score: Double,
    val sourceUrn: String,
    val metadata: Map<String, Any>,
)
