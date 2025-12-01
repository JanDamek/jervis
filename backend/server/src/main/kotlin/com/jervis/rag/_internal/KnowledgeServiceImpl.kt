package com.jervis.rag._internal

import com.jervis.domain.model.ModelTypeEnum
import com.jervis.rag.DocumentResult
import com.jervis.rag.DocumentToStore
import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeSeverity
import com.jervis.rag.KnowledgeType
import com.jervis.rag.SearchRequest
import com.jervis.rag.SearchResult
import com.jervis.rag.StoreRequest
import com.jervis.rag.StoreResult
import com.jervis.rag.StoredDocument
import com.jervis.rag._internal.RagMetadataUpdater
import com.jervis.rag._internal.WeaviateClassNameUtil
import com.jervis.rag._internal.WeaviatePerClientProvisioner
import com.jervis.rag._internal.chunking.ChunkResult
import com.jervis.rag._internal.chunking.TextChunkingService
import com.jervis.rag._internal.model.RagMetadata
import com.jervis.rag._internal.repository.VectorCollection
import com.jervis.rag._internal.repository.VectorDocument
import com.jervis.rag._internal.repository.VectorFilters
import com.jervis.rag._internal.repository.VectorQuery
import com.jervis.rag._internal.repository.VectorStore
import com.jervis.service.gateway.EmbeddingGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Implementation of KnowledgeService.
 * Handles the complete document lifecycle: chunking → embedding → storage → retrieval.
 */
@Service
internal class KnowledgeServiceImpl(
    private val vectorStore: VectorStore,
    private val embeddingGateway: EmbeddingGateway,
    private val chunkingService: TextChunkingService,
    private val ragMetadataUpdater: RagMetadataUpdater,
    private val weaviateProvisioner: WeaviatePerClientProvisioner,
) : KnowledgeService {
    private val logger = KotlinLogging.logger {}

    override suspend fun search(request: SearchRequest): SearchResult {
        logger.info {
            "Knowledge search: query='${request.query}', " +
                "clientId=${request.clientId}, " +
                "projectId=${request.projectId}, " +
                "embeddingType=${request.embeddingType}, " +
                "knowledgeTypes=${request.knowledgeTypes}"
        }

        // Generate embedding
        val modelType = request.embeddingType.toModelType()
        val embedding = embeddingGateway.callEmbedding(modelType, request.query)

        // Build query
        val collection = VectorCollection.from(request.embeddingType)
        val query =
            VectorQuery(
                embedding = embedding,
                limit = request.maxResults,
                minScore = request.minScore.toFloat(),
                filters =
                    VectorFilters(
                        clientId = request.clientId,
                        projectId = request.projectId,
                        knowledgeTypes = request.knowledgeTypes,
                    ),
            )

        weaviateProvisioner.ensureClientCollections(request.clientId)

        val classNameOverride = perClientClassName(request.clientId, request.embeddingType)
        val results =
            vectorStore
                .search(collection, query, classNameOverride)
                .getOrThrow()

        logger.info { "Search completed: found=${results.size} results" }

        // Map to internal fragments and format as text
        val fragments =
            results.map { result ->
                InternalFragment(
                    documentId = result.metadata["documentId"]?.toString() ?: "",
                    content = result.content,
                    type = KnowledgeType.valueOf(result.metadata["knowledgeType"]?.toString() ?: "DOCUMENT"),
                    severity =
                        result.metadata["knowledgeSeverity"]
                            ?.toString()
                            ?.let { KnowledgeSeverity.valueOf(it) },
                    title = result.metadata["documentTitle"]?.toString(),
                    chunkIndex = result.metadata["chunkIndex"]?.toString()?.toIntOrNull(),
                    totalChunks = result.metadata["totalChunks"]?.toString()?.toIntOrNull(),
                )
            }

        return SearchResult(formatFragmentsForLlm(fragments))
    }

    override suspend fun getDocument(documentId: String): DocumentResult {
        logger.info { "Retrieving document: id=$documentId" }

        // Search in both collections
        val fragments =
            coroutineScope {
                listOf(
                    async { searchDocumentInCollection(VectorCollection.TEXT, documentId) },
                    async { searchDocumentInCollection(VectorCollection.CODE, documentId) },
                ).awaitAll()
                    .flatten()
            }

        if (fragments.isEmpty()) {
            throw IllegalArgumentException("Document not found: $documentId")
        }

        // Sort by chunk index
        val sorted = fragments.sortedBy { it.chunkIndex ?: 0 }

        logger.info { "Document retrieved: id=$documentId, fragments=${sorted.size}" }

        return DocumentResult(formatFragmentsForLlm(sorted))
    }

    override suspend fun store(request: StoreRequest): StoreResult {
        logger.info { "Storing ${request.documents.size} documents" }

        val storedDocuments =
            coroutineScope {
                request.documents
                    .map { document ->
                        async {
                            storeDocument(document)
                        }
                    }.awaitAll()
            }

        val totalChunks = storedDocuments.sumOf { it.totalChunks }
        logger.info {
            "Stored ${storedDocuments.size} documents, $totalChunks total chunks"
        }

        return StoreResult(documents = storedDocuments)
    }

    override suspend fun deleteDocument(documentId: String) {
        logger.info { "Deleting document: id=$documentId" }

        // Delete from both collections using filter
        val results =
            coroutineScope {
                listOf(
                    async {
                        vectorStore.deleteByFilter(
                            VectorCollection.TEXT,
                            VectorFilters(
                                clientId =
                                    ObjectId("any"),
                            ),
                        )
                    },
                    async {
                        vectorStore.deleteByFilter(
                            VectorCollection.CODE,
                            VectorFilters(
                                clientId = ObjectId("any"),
                            ),
                        )
                    },
                ).awaitAll()
            }

        val totalDeleted = results.mapNotNull { it.getOrNull() }.sum()
        logger.info { "Document deleted: id=$documentId, fragments=$totalDeleted" }
    }

    // ========== Private Helpers ==========

    /**
     * Store complete document:
     * 1. Chunk the content
     * 2. Generate embeddings for each chunk
     * 3. Store all chunks with same documentId
     */
    private suspend fun storeDocument(document: DocumentToStore): StoredDocument {
        logger.debug {
            "Processing document: id=${document.documentId}, " +
                "type=${document.type}, length=${document.content.length}"
        }

        // 1. Chunk the content
        val chunks = chunkingService.chunk(document.content, document.type)

        logger.info {
            "Document chunked: id=${document.documentId}, chunks=${chunks.size}"
        }

        // 2. Store all chunks in parallel
        val chunkIds =
            coroutineScope {
                chunks
                    .map { chunk ->
                        async {
                            storeChunk(document, chunk)
                        }
                    }.awaitAll()
            }

        return StoredDocument(
            documentId = document.documentId,
            chunkIds = chunkIds,
            totalChunks = chunks.size,
        )
    }

    /**
     * Store single chunk with embedding.
     */
    private suspend fun storeChunk(
        document: DocumentToStore,
        chunk: ChunkResult,
    ): String {
        val knowledgeId = UUID.randomUUID().toString()

        // Generate embedding
        val modelType = document.embeddingType.toModelType()
        val embedding = embeddingGateway.callEmbedding(modelType, chunk.content)

        // Build metadata
        val metadata =
            mutableMapOf<String, Any>(
                "knowledgeId" to knowledgeId,
                "content" to chunk.content,
                "knowledgeType" to document.type.name,
                "documentId" to document.documentId,
                "chunkIndex" to chunk.chunkIndex,
                "totalChunks" to chunk.totalChunks,
                "startOffset" to chunk.startOffset,
                "endOffset" to chunk.endOffset,
                "clientId" to document.clientId.toHexString(),
            )

        document.severity?.let { metadata["knowledgeSeverity"] = it.name }
        document.title?.let { metadata["documentTitle"] = it }
        document.location?.let {
            metadata["documentLocation"] = it // backward compat
            metadata["sourcePath"] = it // new schema field
        }
        document.projectId?.let { metadata["projectId"] = it.toHexString() }

        if (document.entityTypes.isNotEmpty()) {
            metadata["entityTypes"] = document.entityTypes
        }

        if (document.relatedDocs.isNotEmpty()) {
            metadata["relatedDocuments"] = document.relatedDocs
        }

        if (document.graphRefs.isNotEmpty()) {
            metadata["graphRefs"] = document.graphRefs
        }

        // Store
        val collection = VectorCollection.from(document.embeddingType)
        val vectorDoc =
            VectorDocument(
                id = knowledgeId,
                content = chunk.content,
                embedding = embedding,
                metadata = metadata,
            )

        weaviateProvisioner.ensureClientCollections(document.clientId)

        val classNameOverride = perClientClassName(document.clientId, document.embeddingType)
        vectorStore.store(collection, vectorDoc, classNameOverride).getOrThrow()

        // Cross-link RAG -> Graph (best-effort)
        runCatching {
            val meta =
                RagMetadata(
                    projectId = document.projectId?.toHexString() ?: "",
                    sourcePath = document.location ?: "",
                    chunkIndex = chunk.chunkIndex,
                    totalChunks = chunk.totalChunks,
                    entityTypes = document.entityTypes,
                    contentHash = sha256Hex(chunk.content),
                    graphRefs = document.graphRefs,
                )
            ragMetadataUpdater.onChunkStored(
                clientId = document.clientId,
                ragChunkId = knowledgeId,
                meta = meta,
            )
        }

        return knowledgeId
    }

    private suspend fun searchDocumentInCollection(
        collection: VectorCollection,
        documentId: String,
    ): List<InternalFragment> {
        // TODO: Implement proper documentId filter in VectorFilters
        val query =
            VectorQuery(
                embedding = emptyList(),
                limit = 1000,
                minScore = 0f,
                filters =
                    VectorFilters(
                        clientId = org.bson.types.ObjectId(), // Placeholder
                    ),
            )

        val results = vectorStore.search(collection, query).getOrNull() ?: emptyList()

        return results
            .filter { it.metadata["documentId"]?.toString() == documentId }
            .map { result ->
                InternalFragment(
                    documentId = result.metadata["documentId"]?.toString() ?: "",
                    content = result.content,
                    type = KnowledgeType.valueOf(result.metadata["knowledgeType"]?.toString() ?: "DOCUMENT"),
                    severity =
                        result.metadata["knowledgeSeverity"]
                            ?.toString()
                            ?.let { KnowledgeSeverity.valueOf(it) },
                    title = result.metadata["documentTitle"]?.toString(),
                    chunkIndex = result.metadata["chunkIndex"]?.toString()?.toIntOrNull(),
                    totalChunks = result.metadata["totalChunks"]?.toString()?.toIntOrNull(),
                )
            }
    }

    /**
     * Format fragments as text for LLM.
     * IMPORTANT: documentId must be visible so agent can request full document.
     */
    private fun formatFragmentsForLlm(fragments: List<InternalFragment>): String =
        buildString {
            fragments.forEachIndexed { index, fragment ->
                // Header with type and documentId
                append("[${fragment.type}")
                fragment.severity?.let { append(":$it") }
                append("]")

                fragment.title?.let { append(" $it") }

                // DocumentId - CRITICAL for agent to retrieve full document
                append(" (doc:${fragment.documentId})")

                if (fragment.chunkIndex != null && fragment.totalChunks != null) {
                    append(" [část ${fragment.chunkIndex + 1}/${fragment.totalChunks}]")
                }
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

    private fun extractRelatedDocs(metadata: Map<String, Any>): List<String> =
        when (val docs = metadata["relatedDocuments"]) {
            is List<*> -> docs.mapNotNull { it?.toString() }
            is String -> docs.split(",").map { it.trim() }
            else -> emptyList()
        }

    private fun EmbeddingType.toModelType(): ModelTypeEnum =
        when (this) {
            EmbeddingType.TEXT -> ModelTypeEnum.EMBEDDING_TEXT
            EmbeddingType.CODE -> ModelTypeEnum.EMBEDDING_CODE
        }

    private suspend fun perClientClassName(
        clientId: ObjectId,
        embeddingType: EmbeddingType,
    ): String =
        when (embeddingType) {
            EmbeddingType.TEXT -> WeaviateClassNameUtil.textClassFor(clientId)
            EmbeddingType.CODE -> WeaviateClassNameUtil.codeClassFor(clientId)
        }

    private fun sha256Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}

/**
 * Internal fragment representation - only for formatting.
 */
private data class InternalFragment(
    val documentId: String,
    val content: String,
    val type: KnowledgeType,
    val severity: KnowledgeSeverity? = null,
    val title: String? = null,
    val chunkIndex: Int? = null,
    val totalChunks: Int? = null,
)
