package com.jervis.service.rag.pipeline

import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.rag.domain.DocumentChunk
import com.jervis.service.rag.domain.RagQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Vector-based search strategy that queries both text and code embeddings in parallel.
 * Uses Weaviate with hybrid search (BM25 + vector) for better keyword + semantic matching.
 */
@Component
class VectorSearchStrategy(
    private val vectorStorage: VectorStorageRepository,
    private val embeddingGateway: EmbeddingGateway,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val MAX_INITIAL_RESULTS = 500
        private const val MAX_HIT_CONTENT_LENGTH = 2000
    }

    suspend fun search(
        query: RagQuery,
        plan: Plan,
    ): List<DocumentChunk> {
        val (projectId, clientId) = resolveScope(plan)
        logger.info { "VECTOR_SEARCH: Starting for '${query.searchTerms}' with clientId=$clientId, projectId=$projectId" }

        return coroutineScope {
            val textResults = async { searchByModelType(ModelTypeEnum.EMBEDDING_TEXT, query, projectId, clientId) }
            val codeResults = async { searchByModelType(ModelTypeEnum.EMBEDDING_CODE, query, projectId, clientId) }

            (textResults.await() + codeResults.await()).sortedByDescending { it.score }
        }
    }

    private fun resolveScope(plan: Plan): Pair<String?, String?> {
        val clientId = plan.clientDocument.id.toString()
        val projectId =
            plan.projectDocument
                ?.id
                .takeIf { it != plan.clientDocument.id }
                ?.toString()

        return projectId to clientId
    }

    private suspend fun searchByModelType(
        modelTypeEnum: ModelTypeEnum,
        query: RagQuery,
        projectId: String?,
        clientId: String?,
    ): List<DocumentChunk> {
        val embedding = embeddingGateway.callEmbedding(modelTypeEnum, query.searchTerms)

        val results =
            vectorStorage
                .search(
                collectionType = modelTypeEnum,
                query = embedding,
                limit = MAX_INITIAL_RESULTS,
                projectId = projectId,
                clientId = clientId,
                filter = null,
                    useHybridSearch = true,
                    queryText = query.searchTerms,
                )

        logger.info { "VECTOR_SEARCH: $modelTypeEnum returned ${results.size} results for clientId=$clientId, projectId=$projectId" }

        return results.map { result ->
                DocumentChunk(
                    content = extractContent(result).take(MAX_HIT_CONTENT_LENGTH),
                    score = extractScore(result),
                    metadata = extractMetadata(result),
                    embedding = embedding,
                )
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractContent(result: Map<String, Any>): String = result["text"] as? String ?: ""

    @Suppress("UNCHECKED_CAST")
    private fun extractScore(result: Map<String, Any>): Double = (result["_score"] as? Number)?.toDouble() ?: 0.0

    @Suppress("UNCHECKED_CAST")
    private fun extractMetadata(result: Map<String, Any>): Map<String, String> = result.mapValues { (_, value) -> value.toString() }
}
