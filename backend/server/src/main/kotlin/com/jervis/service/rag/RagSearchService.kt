package com.jervis.service.rag

import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.repository.vector.HybridConfig
import com.jervis.repository.vector.VectorQuery
import com.jervis.repository.vector.WeaviateFilters
import com.jervis.repository.vector.WeaviateVectorRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.rag.domain.DocumentChunk
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Modern service for searching the RAG system.
 *
 * Features:
 * - Flow-based reactive search
 * - Parallel multi-model search (TEXT + CODE)
 * - Hybrid search support (BM25 + vector)
 * - Clean, Kotlin-idiomatic API
 *
 * Replaces complex VectorSearchStrategy with simpler, more powerful interface.
 */
@Service
class RagSearchService(
    private val vectorRepo: WeaviateVectorRepository,
    private val embeddingGateway: EmbeddingGateway,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        private const val MAX_INITIAL_RESULTS = 500
        private const val MAX_HIT_CONTENT_LENGTH = 2000
    }

    /**
     * Search for documents using Flow for reactive/streaming results.
     * Searches a single model type (TEXT or CODE).
     */
    suspend fun search(
        query: String,
        modelType: ModelTypeEnum,
        context: SearchContext,
    ): Flow<DocumentChunk> {
        val vectorQuery = buildQuery(query, modelType, context)

        return vectorRepo
            .search(
                collectionType = modelType,
                query = vectorQuery,
            ).map { result ->
                DocumentChunk(
                    content = result.text.take(MAX_HIT_CONTENT_LENGTH),
                    score = result.score,
                    metadata = result.metadata.mapValues { it.value.toString() },
                    embedding = emptyList(), // Not needed for search results
                )
            }
    }

    /**
     * Search across both TEXT and CODE models in parallel.
     * Returns combined, score-sorted results.
     */
    suspend fun hybridSearch(
        query: String,
        context: SearchContext,
    ): List<DocumentChunk> =
        coroutineScope {
            logger.info {
                "Starting hybrid search: query='$query', clientId=${context.clientId}, " +
                    "projectId=${context.projectId}"
            }

            // Search TEXT and CODE in parallel
            val textResults =
                async {
                    searchModelType(query, ModelTypeEnum.EMBEDDING_TEXT, context)
                }
            val codeResults =
                async {
                    searchModelType(query, ModelTypeEnum.EMBEDDING_CODE, context)
                }

            // Combine and sort by score
            val textList = textResults.await()
            val codeList = codeResults.await()
            val combined = textList + codeList
            val sorted = combined.sortedByDescending { it.score }

            logger.info {
                "Hybrid search complete: text=${textList.size}, " +
                    "code=${codeList.size}, total=${sorted.size}"
            }

            sorted
        }

    /**
     * Search a single model type (internal helper)
     */
    private suspend fun searchModelType(
        query: String,
        modelType: ModelTypeEnum,
        context: SearchContext,
    ): List<DocumentChunk> {
        val results =
            vectorRepo.searchAll(
                collectionType = modelType,
                query = buildQuery(query, modelType, context),
            )

        logger.debug {
            "Search completed for $modelType: found=${results.size}, " +
                "clientId=${context.clientId}, projectId=${context.projectId}"
        }

        return results.map { result ->
            DocumentChunk(
                content = result.text.take(MAX_HIT_CONTENT_LENGTH),
                score = result.score,
                metadata = result.metadata.mapValues { it.value.toString() },
                embedding = emptyList(),
            )
        }
    }

    /**
     * Build VectorQuery from search parameters
     */
    private suspend fun buildQuery(
        query: String,
        modelType: ModelTypeEnum,
        context: SearchContext,
    ): VectorQuery {
        // Generate embedding for query
        val embedding = embeddingGateway.callEmbedding(modelType, query)

        logger.info {
            "Generated embedding for query '$query' ($modelType): " +
                "size=${embedding.size}, first3=${embedding.take(3)}, sum=${embedding.sum()}"
        }

        // Build filters
        val filters =
            WeaviateFilters(
                clientId = context.clientId,
                projectId = context.projectId,
                branch = context.branch,
                ragSourceType = context.ragSourceType,
            )

        // Hybrid search config
        val hybridConfig =
            if (context.useHybridSearch) {
                HybridConfig(
                    queryText = query,
                    alpha = context.hybridAlpha,
                )
            } else {
                null
            }

        return VectorQuery(
            embedding = embedding,
            filters = filters,
            limit = context.limit,
            minScore = context.minScore,
            hybridSearch = hybridConfig,
        )
    }
}

/**
 * Search context with filters and configuration
 */
data class SearchContext(
    val clientId: String,
    val projectId: String? = null,
    val branch: String? = null,
    val ragSourceType: com.jervis.domain.rag.RagSourceType? = null,
    val limit: Int = 500,
    val minScore: Float = 0.0f,
    val useHybridSearch: Boolean = true,
    val hybridAlpha: Double = 0.75,
) {
    companion object {
        /**
         * Create SearchContext from Plan
         */
        fun fromPlan(plan: Plan): SearchContext =
            SearchContext(
                clientId = plan.clientDocument.id.toString(),
                projectId =
                    plan.projectDocument
                        ?.id
                        ?.takeIf { it != plan.clientDocument.id }
                        ?.toString(),
            )
    }
}
