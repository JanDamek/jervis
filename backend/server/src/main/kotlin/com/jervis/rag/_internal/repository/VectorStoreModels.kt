package com.jervis.rag._internal.repository

import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeType
import org.bson.types.ObjectId

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
    val clientId: ObjectId,
    val projectId: ObjectId? = null,
    val knowledgeTypes: Set<KnowledgeType>? = null,
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

/**
 * Collection type for vector store.
 */
internal enum class VectorCollection(
    val collectionName: String,
) {
    TEXT("KnowledgeText"),
    CODE("KnowledgeCode"),
    ;

    companion object {
        fun from(embeddingType: EmbeddingType): VectorCollection =
            when (embeddingType) {
                EmbeddingType.TEXT -> TEXT
                EmbeddingType.CODE -> CODE
            }
    }
}
