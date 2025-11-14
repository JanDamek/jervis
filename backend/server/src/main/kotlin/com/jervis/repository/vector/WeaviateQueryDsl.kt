package com.jervis.repository.vector

import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagSourceType
import io.weaviate.client.v1.filters.Operator
import io.weaviate.client.v1.filters.WhereFilter

/**
 * Type-safe DSL for building Weaviate queries and filters.
 * Provides a clean, Kotlin-idiomatic way to construct complex search queries.
 */
data class VectorQuery(
    val embedding: List<Float>,
    val filters: WeaviateFilters = WeaviateFilters(),
    val limit: Int = 100,
    val minScore: Float = 0.0f,
    val hybridSearch: HybridConfig? = null,
)

/**
 * Hybrid search configuration (BM25 + vector)
 */
data class HybridConfig(
    val queryText: String,
    val alpha: Double = 0.75, // 0.75 = 75% vector, 25% BM25
)

/**
 * Type-safe filter builder for Weaviate WHERE clauses
 */
data class WeaviateFilters(
    val clientId: String? = null,
    val projectId: String? = null,
    val branch: String? = null,
    val ragSourceType: RagSourceType? = null,
    val fileName: String? = null,
    val commitHash: String? = null,
    val custom: Map<String, String> = emptyMap(),
) {
    /**
     * Convert to Weaviate WhereFilter
     * Returns null if no filters specified
     */
    fun toWhereFilter(): WhereFilter? {
        val conditions =
            buildList {
                clientId?.let { add(equalFilter("clientId", it)) }
                projectId?.let { add(equalFilter("projectId", it)) }
                branch?.let { add(equalFilter("branch", it)) }
                ragSourceType?.let { add(equalFilter("ragSourceType", it.name)) }
                fileName?.let { add(equalFilter("fileName", it)) }
                // Weaviate schema uses "gitCommitHash" property name
                commitHash?.let { add(equalFilter("gitCommitHash", it)) }
                custom.forEach { (key, value) -> add(equalFilter(key, value)) }
            }

        return when {
            conditions.isEmpty() -> null
            conditions.size == 1 -> conditions.first()
            else ->
                WhereFilter
                    .builder()
                    .operator(Operator.And)
                    .operands(*conditions.toTypedArray())
                    .build()
        }
    }

    private fun equalFilter(
        path: String,
        value: String,
    ): WhereFilter =
        WhereFilter
            .builder()
            .path(path)
            .operator(Operator.Equal)
            .valueText(value)
            .build()
}

/**
 * Search result from Weaviate
 */
data class SearchResult(
    val id: String,
    val text: String,
    val score: Double,
    val metadata: Map<String, Any>,
)

/**
 * Collection name resolver based on ModelType
 */
object WeaviateCollections {
    fun forModelType(modelType: ModelTypeEnum): String =
        when (modelType) {
            ModelTypeEnum.EMBEDDING_TEXT -> "SemanticText"
            ModelTypeEnum.EMBEDDING_CODE -> "SemanticCode"
            else -> throw IllegalArgumentException("Unsupported collection type: $modelType")
        }
}

class WeaviateFiltersBuilder {
    var clientId: String? = null
    var projectId: String? = null
    var branch: String? = null
    var ragSourceType: RagSourceType? = null
    var fileName: String? = null
    var commitHash: String? = null
    var custom: MutableMap<String, String> = mutableMapOf()

    fun build(): WeaviateFilters =
        WeaviateFilters(
            clientId = clientId,
            projectId = projectId,
            branch = branch,
            ragSourceType = ragSourceType,
            fileName = fileName,
            commitHash = commitHash,
            custom = custom,
        )
}
