package com.jervis.service.rag

import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.repository.vector.WeaviateVectorRepository
import com.jervis.service.gateway.EmbeddingGateway
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * High-level service for indexing documents into the RAG system.
 *
 * Responsibilities:
 * - Coordinate embedding generation and vector storage
 * - Handle batch indexing with progress tracking
 * - Provide smart reindexing (only index if content changed)
 * - Track indexed documents in MongoDB via VectorStoreIndexService
 *
 * Simplifies caller code by encapsulating the full indexing pipeline:
 * Document → Embedding → Vector Store → Index Tracking
 */
@Service
class RagIndexingService(
    private val vectorRepo: WeaviateVectorRepository,
    private val embeddingGateway: EmbeddingGateway,
    private val indexService: VectorStoreIndexService,
    private val debugService: com.jervis.service.debug.DebugService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Index a single document with automatic embedding generation.
     * Returns Result with indexed document info or error.
     */
    suspend fun indexDocument(
        document: RagDocument,
        modelType: ModelTypeEnum,
    ): Result<IndexedDocument> =
        runCatching {
            logger.debug {
                "Indexing document: sourceType=${document.ragSourceType}, " +
                    "client=${document.clientId}, project=${document.projectId}, " +
                    "branch=${document.branch}, textLength=${document.text.length}"
            }

            // Emit start event if correlationId is present
            document.correlationId?.let {
                debugService.indexingStart(
                    correlationId = it,
                    sourceType = document.ragSourceType.name,
                    modelType = modelType.name,
                    sourceUri = document.sourceUri,
                    textLength = document.text.length,
                )
            }

            // Generate embedding
            val embedding = embeddingGateway.callEmbedding(modelType, document.text)

            // Emit embedding generated event
            document.correlationId?.let {
                debugService.embeddingGenerated(
                    correlationId = it,
                    modelType = modelType.name,
                    vectorDim = embedding.size,
                    textLength = document.text.length,
                )
            }

            // Store in Weaviate
            val vectorStoreId =
                vectorRepo
                    .store(modelType, document, embedding)
                    .getOrThrow()

            // Emit vector stored event
            document.correlationId?.let {
                debugService.vectorStored(
                    correlationId = it,
                    modelType = modelType.name,
                    vectorStoreId = vectorStoreId,
                )
            }

            // Track in MongoDB (if projectId is present)
            document.projectId?.let { projectId ->
                indexService.trackIndexed(
                    projectId = projectId,
                    clientId = document.clientId,
                    branch = document.branch,
                    sourceType = document.ragSourceType,
                    sourceId = document.sourceUri ?: vectorStoreId,
                    vectorStoreId = vectorStoreId,
                    vectorStoreName = "${document.ragSourceType.name.lowercase()}-${vectorStoreId.take(8)}",
                    content = document.text,
                    filePath = document.fileName,
                    symbolName = null,
                    commitHash = null,
                )
            }

            logger.info {
                "Document indexed successfully: vectorStoreId=$vectorStoreId, " +
                    "sourceType=${document.ragSourceType}, collection=$modelType"
            }

            // Emit completed event
            document.correlationId?.let { debugService.indexingCompleted(it, success = true, errorMessage = null) }

            IndexedDocument(
                vectorStoreId = vectorStoreId,
                modelType = modelType,
                sourceType = document.ragSourceType,
                clientId = document.clientId,
                projectId = document.projectId,
            )
        }
}

/**
 * Information about an indexed document
 */
data class IndexedDocument(
    val vectorStoreId: String,
    val modelType: ModelTypeEnum,
    val sourceType: RagSourceType,
    val clientId: ObjectId,
    val projectId: ObjectId?,
)

/**
 * Result of batch indexing operation
 */
sealed class IndexResult {
    abstract val index: Int
    abstract val total: Int

    data class Success(
        override val index: Int,
        override val total: Int,
        val indexed: IndexedDocument,
    ) : IndexResult()

    data class Error(
        override val index: Int,
        override val total: Int,
        val error: String,
    ) : IndexResult()
}

/**
 * Result of reindexing operation
 */
sealed class ReindexResult {
    data class Reindexed(
        val indexed: IndexedDocument,
    ) : ReindexResult()

    data class Skipped(
        val reason: String,
    ) : ReindexResult()

    data class Failed(
        val error: String,
    ) : ReindexResult()
}
