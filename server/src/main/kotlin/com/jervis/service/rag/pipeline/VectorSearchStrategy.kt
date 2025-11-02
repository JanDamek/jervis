package com.jervis.service.rag.pipeline

import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.rag.domain.DocumentChunk
import com.jervis.service.rag.domain.RagQuery
import io.qdrant.client.grpc.JsonWithInt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Vector-based search strategy that queries both text and code embeddings in parallel.
 */
@Component
class VectorSearchStrategy(
    private val vectorStorage: VectorStorageRepository,
    private val embeddingGateway: EmbeddingGateway,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val MAX_INITIAL_RESULTS = 100
        private const val MAX_HIT_CONTENT_LENGTH = 2000
    }

    suspend fun search(
        query: RagQuery,
        plan: Plan,
    ): List<DocumentChunk> {
        logger.debug { "VECTOR_SEARCH: Starting for '${query.searchTerms}'" }

        val (projectId, clientId) = resolveScope(plan)

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

        return vectorStorage
            .search(
                collectionType = modelTypeEnum,
                query = embedding,
                limit = MAX_INITIAL_RESULTS,
                projectId = projectId,
                clientId = clientId,
                filter = null,
            ).map { result ->
                DocumentChunk(
                    content = extractContent(result).take(MAX_HIT_CONTENT_LENGTH),
                    score = extractScore(result),
                    metadata = extractMetadata(result),
                    embedding = embedding,
                )
            }
    }

    private fun extractContent(result: Map<String, JsonWithInt.Value>): String =
        result["summary"]?.stringValue ?: result["content"]?.stringValue ?: ""

    private fun extractScore(result: Map<String, JsonWithInt.Value>): Double = result["_score"]?.doubleValue ?: 0.0

    private fun extractMetadata(result: Map<String, JsonWithInt.Value>): Map<String, String> =
        result.mapValues { (_, value) ->
            when {
                value.hasStringValue() -> value.stringValue
                value.hasIntegerValue() -> value.integerValue.toString()
                value.hasDoubleValue() -> value.doubleValue.toString()
                value.hasBoolValue() -> value.boolValue.toString()
                else -> value.toString()
            }
        }
}
