package com.jervis.rag.internal

import com.jervis.rag.HybridSearchRequest
import com.jervis.rag.KnowledgeService
import com.jervis.rag.SearchRequest
import com.jervis.rag.SearchResult
import com.jervis.rag.StoreChunkRequest
import com.jervis.rag.internal.model.RagMetadata
import com.jervis.rag.internal.repository.VectorDocument
import com.jervis.rag.internal.repository.VectorFilters
import com.jervis.rag.internal.repository.VectorQuery
import com.jervis.rag.internal.repository.WeaviateVectorStore
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.types.ClientId
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Implementation of KnowledgeService.
 * Atomic storage: Agent chunks, service embeds.
 * Hybrid search: BM25 + Vector.
 */
@Service
internal class KnowledgeServiceImpl(
    private val weaviateVectorStore: WeaviateVectorStore,
    private val embeddingGateway: EmbeddingGateway,
    private val ragMetadataUpdater: RagMetadataUpdater,
    private val weaviateProvisioner: WeaviatePerClientProvisioner,
) : KnowledgeService {
    private val logger = KotlinLogging.logger {}

    override suspend fun storeChunk(request: StoreChunkRequest): String {
        logger.info {
            "ATOMIC_STORE_CHUNK: clientId=${request.clientId}, " +
                "size=${request.content.length} "
        }

        if (request.content.isBlank()) {
            throw IllegalArgumentException("Content cannot be blank")
        }

        val chunkId = UUID.randomUUID().toString()

        // 1. Generate embedding
        val embedding = embeddingGateway.callEmbedding(request.content)

        // 2. Build metadata
        val metadata =
            mutableMapOf<String, Any>(
                "knowledgeId" to chunkId,
                "content" to request.content,
                "sourceUrn" to request.sourceUrn.value,
                "clientId" to request.clientId.toString(),
            )

        request.projectId?.let { metadata["projectId"] = it.toString() }

        if (request.graphRefs.isNotEmpty()) {
            metadata["graphRefs"] = request.graphRefs
        }

        // 3. Store in Weaviate (with BM25 indexing on the 'content' field)
        val vectorDoc =
            VectorDocument(
                id = chunkId,
                content = request.content,
                embedding = embedding,
                metadata = metadata,
            )

        weaviateProvisioner.ensureClientCollections(request.clientId)

        val classNameOverride = perClientClassName(request.clientId)
        weaviateVectorStore.store(vectorDoc, classNameOverride).getOrThrow()

        // 4. Cross-link RAG -> Graph (best-effort)
        runCatching {
            val meta =
                RagMetadata(
                    clientId = request.clientId,
                    projectId = request.projectId,
                    sourceUrn = request.sourceUrn,
                    graphRefs = request.graphRefs,
                )
            ragMetadataUpdater.onChunkStored(
                clientId = request.clientId,
                ragChunkId = chunkId,
                meta = meta,
            )
        }.onFailure { e ->
            logger.warn(e) { "Graph cross-link failed for chunk $chunkId (non-fatal)" }
        }

        logger.info { "CHUNK_STORED: chunkId=$chunkId, sourceUrn=${request.sourceUrn}" }
        return chunkId
    }

    override suspend fun searchHybrid(request: HybridSearchRequest): SearchResult {
        logger.info {
            "HYBRID_SEARCH: query='${request.query}', " +
                "alpha=${request.alpha}, " +
                "clientId=${request.clientId}, " +
                "maxResults=${request.maxResults}"
        }

        // For now, delegate to existing vector search
        // TODO: Implement true hybrid search with BM25 + Vector when Weaviate client supports it
        // Weaviate v4 Java client may need GraphQL query builder for hybrid argument

        val embedding = embeddingGateway.callEmbedding(request.query)

        val query =
            VectorQuery(
                embedding = embedding,
                limit = request.maxResults,
                minScore = 0.0f, // Hybrid search uses alpha, not minScore
                filters =
                    VectorFilters(
                        clientId = request.clientId,
                        projectId = request.projectId,
                    ),
            )

        weaviateProvisioner.ensureClientCollections(request.clientId)

        val classNameOverride = perClientClassName(request.clientId)
        val results =
            weaviateVectorStore
                .search(query, classNameOverride)
                .getOrThrow()

        logger.info { "HYBRID_SEARCH_COMPLETE: found=${results.size} results" }

        val fragments =
            results.map { result ->
                InternalFragment(
                    sourceUrn = result.metadata["sourceUrn"]?.toString() ?: "",
                    content = result.content,
                )
            }

        return SearchResult(formatFragmentsForLlm(fragments))
    }

    override suspend fun search(request: SearchRequest): SearchResult {
        logger.info {
            "Knowledge search: query='${request.query}', " +
                "clientId=${request.clientId}, " +
                "projectId=${request.projectId}"
        }

        // Generate embedding
        val embedding = embeddingGateway.callEmbedding(request.query)

        // Build query
        val query =
            VectorQuery(
                embedding = embedding,
                limit = request.maxResults,
                minScore = request.minScore.toFloat(),
                filters =
                    VectorFilters(
                        clientId = request.clientId,
                        projectId = request.projectId,
                    ),
            )

        weaviateProvisioner.ensureClientCollections(request.clientId)

        val classNameOverride = perClientClassName(request.clientId)
        val results =
            weaviateVectorStore
                .search(query, classNameOverride)
                .getOrThrow()

        logger.info { "Search completed: found=${results.size} results" }

        // Map to internal fragments and format as text
        val fragments =
            results.map { result ->
                InternalFragment(
                    sourceUrn = result.metadata["sourceUrn"]?.toString() ?: "",
                    content = result.content,
                )
            }

        return SearchResult(formatFragmentsForLlm(fragments))
    }

    /**
     * Format fragments as text for LLM.
     * IMPORTANT: sourceUrn must be visible, so the agent can track sources.
     */
    private fun formatFragmentsForLlm(fragments: List<InternalFragment>): String =
        buildString {
            fragments.forEachIndexed { index, fragment ->
                // Header with sourceUrn
                append("[")
                append(fragment.sourceUrn)
                append("]")
                appendLine()

                // Content
                appendLine(fragment.content)

                // Separator between fragments
                if (index < fragments.size - 1) {
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }
        }

    private suspend fun perClientClassName(clientId: ClientId): String = WeaviateClassNameUtil.classFor(clientId)

    /**
     * Internal fragment representation - only for formatting.
     */
    private data class InternalFragment(
        val sourceUrn: String,
        val content: String,
    )
}
