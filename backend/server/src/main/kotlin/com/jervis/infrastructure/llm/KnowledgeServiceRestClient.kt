package com.jervis.infrastructure.llm

import com.jervis.contracts.knowledgebase.DocumentCategory
import com.jervis.contracts.knowledgebase.DocumentState
import com.jervis.infrastructure.grpc.KbDocumentGrpcClient
import com.jervis.infrastructure.grpc.KbGraphGrpcClient
import com.jervis.infrastructure.grpc.KbIngestGrpcClient
import com.jervis.infrastructure.grpc.KbMaintenanceGrpcClient
import com.jervis.infrastructure.grpc.KbQueueGrpcClient
import com.jervis.infrastructure.grpc.KbRetrieveGrpcClient
import com.jervis.infrastructure.llm.KnowledgeServiceRestClient
import com.jervis.common.types.ClientId
import com.jervis.knowledgebase.KnowledgeService
import com.jervis.knowledgebase.model.CpgIngestRequest
import com.jervis.knowledgebase.model.CpgIngestResult
import com.jervis.knowledgebase.model.EvidenceItem
import com.jervis.knowledgebase.model.GitCommitIngestRequest
import com.jervis.knowledgebase.model.GitCommitIngestResult
import com.jervis.knowledgebase.model.EvidencePack
import com.jervis.knowledgebase.model.FullIngestRequest
import com.jervis.knowledgebase.model.FullIngestResult
import com.jervis.knowledgebase.model.GitStructureIngestRequest
import com.jervis.knowledgebase.model.GitStructureIngestResult
import com.jervis.knowledgebase.model.IngestRequest
import com.jervis.knowledgebase.model.IngestResult
import com.jervis.knowledgebase.model.RetrievalRequest
import com.jervis.knowledgebase.service.graphdb.model.GraphNode
import com.jervis.knowledgebase.service.graphdb.model.TraversalSpec
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.delay
import mu.KotlinLogging
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * REST client for Python knowledgebase microservice.
 *
 * The Python service uses FastAPI with REST endpoints instead of KRPC.
 * This client bridges the Kotlin KnowledgeService interface to REST calls.
 */
class KnowledgeServiceRestClient(
    private val baseUrl: String,
    private val callbackBaseUrl: String = "",
    private val kbMaintenanceGrpc: KbMaintenanceGrpcClient? = null,
    private val kbQueueGrpc: KbQueueGrpcClient? = null,
    private val kbIngestGrpc: KbIngestGrpcClient? = null,
    private val kbRetrieveGrpc: KbRetrieveGrpcClient? = null,
    private val kbGraphGrpc: KbGraphGrpcClient? = null,
    private val kbDocumentGrpc: KbDocumentGrpcClient? = null,
) : KnowledgeService {

    private fun categoryToString(c: DocumentCategory): String = when (c) {
        DocumentCategory.DOCUMENT_CATEGORY_TECHNICAL -> "TECHNICAL"
        DocumentCategory.DOCUMENT_CATEGORY_BUSINESS -> "BUSINESS"
        DocumentCategory.DOCUMENT_CATEGORY_LEGAL -> "LEGAL"
        DocumentCategory.DOCUMENT_CATEGORY_PROCESS -> "PROCESS"
        DocumentCategory.DOCUMENT_CATEGORY_MEETING_NOTES -> "MEETING_NOTES"
        DocumentCategory.DOCUMENT_CATEGORY_REPORT -> "REPORT"
        DocumentCategory.DOCUMENT_CATEGORY_SPECIFICATION -> "SPECIFICATION"
        else -> "OTHER"
    }

    private fun stateToString(s: DocumentState): String = when (s) {
        DocumentState.DOCUMENT_STATE_UPLOADED -> "UPLOADED"
        DocumentState.DOCUMENT_STATE_EXTRACTED -> "EXTRACTED"
        DocumentState.DOCUMENT_STATE_INDEXED -> "INDEXED"
        DocumentState.DOCUMENT_STATE_FAILED -> "FAILED"
        else -> "UPLOADED"
    }

    private fun protoToDto(d: com.jervis.contracts.knowledgebase.Document): PythonKbDocumentDto =
        PythonKbDocumentDto(
            id = d.id,
            clientId = d.clientId,
            projectId = d.projectId.takeIf { it.isNotEmpty() },
            filename = d.filename,
            mimeType = d.mimeType,
            sizeBytes = d.sizeBytes,
            storagePath = d.storagePath,
            state = stateToString(d.state),
            category = categoryToString(d.category),
            title = d.title.takeIf { it.isNotEmpty() },
            description = d.description.takeIf { it.isNotEmpty() },
            tags = d.tagsList,
            extractedTextPreview = d.extractedTextPreview.takeIf { it.isNotEmpty() },
            pageCount = d.pageCount.takeIf { it > 0 },
            contentHash = d.contentHash.takeIf { it.isNotEmpty() },
            sourceUrn = d.sourceUrn,
            errorMessage = d.errorMessage.takeIf { it.isNotEmpty() },
            ragChunks = d.ragChunksList,
            uploadedAt = d.uploadedAtIso,
            indexedAt = d.indexedAtIso.takeIf { it.isNotEmpty() },
        )
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                },
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 30_000   // 30s connect timeout only
            socketTimeoutMillis = Long.MAX_VALUE // KB embedding trvá jak trvá, čekáme na GPU
        }
    }

    companion object {
        /** Max retries for transient failures (SocketTimeout, connection reset). */
        private const val MAX_RETRIES = 2
        /** Delay between retries (ms). Doubles on each retry. */
        private const val RETRY_BASE_DELAY_MS = 5_000L
    }

    private val apiBaseUrl = baseUrl.trimEnd('/') + "/api/v1"

    override suspend fun ingest(request: IngestRequest): IngestResult {
        logger.debug { "Calling knowledgebase ingest: sourceUrn=${request.sourceUrn}" }
        return try {
            val proto = ingestGrpc().ingest(request)
            IngestResult(
                success = proto.status == "success",
                summary = "Ingested ${proto.chunksCount} chunks, created ${proto.nodesCreated} nodes and ${proto.edgesCreated} edges",
                ingestedNodes = emptyList(),
                error = if (proto.status != "success") proto.status else null,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to ingest to knowledgebase: ${e.message}" }
            IngestResult(
                success = false,
                summary = "Ingestion failed",
                error = e.message,
            )
        }
    }

    override suspend fun ingestFull(request: FullIngestRequest): FullIngestResult {
        logger.debug { "Calling knowledgebase ingestFull: sourceUrn=${request.sourceUrn}, attachments=${request.attachments.size}" }
        var lastException: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                val delayMs = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                logger.info { "KB ingestFull retry $attempt/$MAX_RETRIES after ${delayMs}ms (sourceUrn=${request.sourceUrn})" }
                delay(delayMs)
            }
            try {
                val proto = ingestGrpc().ingestFull(request)
                if (attempt > 0) {
                    logger.info { "KB ingestFull succeeded on retry $attempt (sourceUrn=${request.sourceUrn})" }
                }
                return FullIngestResult(
                    success = proto.status == "success",
                    chunksCount = proto.chunksCount,
                    nodesCreated = proto.nodesCreated,
                    edgesCreated = proto.edgesCreated,
                    attachmentsProcessed = proto.attachmentsProcessed,
                    attachmentsFailed = proto.attachmentsFailed,
                    summary = proto.summary,
                    entities = proto.entitiesList,
                    hasActionableContent = proto.hasActionableContent,
                    suggestedActions = proto.suggestedActionsList,
                    hasFutureDeadline = proto.hasFutureDeadline,
                    suggestedDeadline = proto.suggestedDeadline.takeIf { it.isNotEmpty() },
                    isAssignedToMe = proto.isAssignedToMe,
                    urgency = proto.urgency,
                )
            } catch (e: io.grpc.StatusException) {
                val retryable = e.status.code in setOf(
                    io.grpc.Status.Code.UNAVAILABLE,
                    io.grpc.Status.Code.DEADLINE_EXCEEDED,
                )
                if (retryable) {
                    logger.warn { "KB ingestFull transient error (attempt $attempt, status=${e.status.code}): ${e.message}" }
                    lastException = e
                } else {
                    logger.error(e) { "Failed to ingestFull to knowledgebase (non-retryable): ${e.message}" }
                    return FullIngestResult(
                        success = false,
                        chunksCount = 0,
                        nodesCreated = 0,
                        edgesCreated = 0,
                        attachmentsProcessed = 0,
                        attachmentsFailed = 0,
                        summary = "Ingestion failed",
                        error = e.message,
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to ingestFull to knowledgebase (non-retryable): ${e.message}" }
                return FullIngestResult(
                    success = false,
                    chunksCount = 0,
                    nodesCreated = 0,
                    edgesCreated = 0,
                    attachmentsProcessed = 0,
                    attachmentsFailed = 0,
                    summary = "Ingestion failed",
                    error = e.message,
                )
            }
        }
        logger.error(lastException) { "Failed to ingestFull after $MAX_RETRIES retries: ${lastException?.message}" }
        return FullIngestResult(
            success = false,
            chunksCount = 0,
            nodesCreated = 0,
            edgesCreated = 0,
            attachmentsProcessed = 0,
            attachmentsFailed = 0,
            summary = "Ingestion failed after $MAX_RETRIES retries",
            error = lastException?.message,
        )
    }

    /**
     * Submit full ingest request to KB asynchronously (fire-and-forget).
     *
     * KB processes in background and dials ServerKbCallbacksService.KbDone
     * when processing finishes; KbProgress events stream along the way.
     * Returns true if KB accepted the request.
     */
    suspend fun submitFullIngestAsync(
        request: FullIngestRequest,
        taskId: String,
        clientId: String,
        priority: Int? = null,
        maxTier: String = "NONE",
    ): Boolean {
        logger.info { "KB_ASYNC_SUBMIT: taskId=$taskId sourceUrn=${request.sourceUrn}" }
        return try {
            val ack = ingestGrpc().ingestFullAsync(
                request = request,
                taskId = taskId,
                clientId = clientId,
                priority = priority,
                maxTier = maxTier,
            )
            if (ack.accepted) {
                logger.info { "KB_ASYNC_SUBMIT: accepted taskId=$taskId detail=${ack.detail}" }
            } else {
                logger.error { "KB_ASYNC_SUBMIT: rejected taskId=$taskId detail=${ack.detail}" }
            }
            ack.accepted
        } catch (e: Exception) {
            logger.error(e) { "KB_ASYNC_SUBMIT: failed taskId=$taskId: ${e.message}" }
            false
        }
    }

    override suspend fun retrieve(request: RetrievalRequest): EvidencePack {
        logger.debug { "Calling knowledgebase retrieve: query=${request.query}" }
        val grpc = kbRetrieveGrpc ?: run {
            logger.warn { "KB retrieve gRPC client not wired" }
            return EvidencePack(items = emptyList(), summary = "Retrieval client not wired")
        }
        return try {
            val pack = grpc.retrieve(
                query = request.query,
                clientId = request.clientId.toString(),
                projectId = request.projectId?.toString() ?: "",
                groupId = request.groupId ?: "",
                asOfIso = request.asOf?.let { DateTimeFormatter.ISO_INSTANT.format(it) } ?: "",
                minConfidence = request.minConfidence,
                maxResults = request.maxResults,
            )
            EvidencePack(
                items = pack.itemsList.map { item ->
                    EvidenceItem(
                        source = item.sourceUrn,
                        content = item.content,
                        confidence = item.score,
                        metadata = item.metadataMap,
                    )
                },
                summary = "Retrieved ${pack.itemsCount} items",
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to retrieve from knowledgebase: ${e.message}" }
            EvidencePack(items = emptyList(), summary = "Retrieval failed: ${e.message}")
        }
    }

    override suspend fun traverse(
        clientId: ClientId,
        startKey: String,
        spec: TraversalSpec,
    ): List<GraphNode> {
        logger.debug { "Calling knowledgebase traverse: startKey=$startKey" }
        val grpc = kbGraphGrpc ?: run {
            logger.warn { "KB graph gRPC client not wired" }
            return emptyList()
        }
        return try {
            val resp = grpc.traverse(
                clientId = clientId.toString(),
                startKey = startKey,
                direction = spec.direction.name,
                minDepth = 1,
                maxDepth = spec.maxDepth,
                edgeCollection = spec.edgeTypes?.firstOrNull() ?: "",
            )
            resp.nodesList.map { node ->
                GraphNode(
                    key = node.key,
                    entityType = node.label,
                    ragChunks = emptyList(),
                    properties = node.propertiesMap.mapValues { it.value as Any },
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to traverse knowledgebase: ${e.message}" }
            emptyList()
        }
    }

    private fun ingestGrpc(): KbIngestGrpcClient =
        kbIngestGrpc ?: error("KbIngestGrpcClient not wired — see RpcClientsConfig.getKnowledgeService()")

    override suspend fun ingestGitStructure(request: GitStructureIngestRequest): GitStructureIngestResult {
        logger.debug { "Calling knowledgebase ingestGitStructure: repo=${request.repositoryIdentifier} branch=${request.branch}" }
        return try {
            val proto = ingestGrpc().ingestGitStructure(request)
            GitStructureIngestResult(
                status = proto.status,
                nodesCreated = proto.nodesCreated,
                edgesCreated = proto.edgesCreated,
                nodesUpdated = proto.nodesUpdated,
                repositoryKey = proto.repositoryKey,
                branchKey = proto.branchKey,
                filesIndexed = proto.filesIndexed,
                classesIndexed = proto.classesIndexed,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to ingest git structure to knowledgebase: ${e.message}" }
            GitStructureIngestResult(status = "error: ${e.message}")
        }
    }

    override suspend fun ingestGitCommits(request: GitCommitIngestRequest): GitCommitIngestResult {
        logger.debug { "Calling knowledgebase ingestGitCommits: repo=${request.repositoryIdentifier} branch=${request.branch} commits=${request.commits.size}" }
        return try {
            val proto = ingestGrpc().ingestGitCommits(request)
            GitCommitIngestResult(
                status = proto.status,
                commitsIngested = proto.commitsIngested,
                nodesCreated = proto.nodesCreated,
                edgesCreated = proto.edgesCreated,
                ragChunks = proto.ragChunks,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to ingest git commits to knowledgebase: ${e.message}" }
            GitCommitIngestResult(status = "error: ${e.message}")
        }
    }

    override suspend fun ingestCpg(request: CpgIngestRequest): CpgIngestResult {
        logger.debug { "Calling knowledgebase ingestCpg: project=${request.projectId} branch=${request.branch}" }
        return try {
            val proto = ingestGrpc().ingestCpg(request)
            CpgIngestResult(
                status = proto.status,
                methodsEnriched = proto.methodsEnriched,
                extendsEdges = proto.extendsEdges,
                callsEdges = proto.callsEdges,
                usesTypeEdges = proto.usesTypeEdges,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to ingest CPG to knowledgebase: ${e.message}" }
            CpgIngestResult(status = "error: ${e.message}")
        }
    }

    override suspend fun purge(sourceUrn: String): Boolean {
        logger.debug { "Calling knowledgebase purge: sourceUrn=$sourceUrn" }
        return try {
            val result = ingestGrpc().purge(sourceUrn)
            logger.info {
                "Purge complete: chunks=${result.chunksDeleted} " +
                    "nodes_cleaned=${result.nodesCleaned} edges_cleaned=${result.edgesCleaned} " +
                    "nodes_deleted=${result.nodesDeleted} edges_deleted=${result.edgesDeleted}"
            }
            result.status == "success"
        } catch (e: Exception) {
            logger.error(e) { "Failed to purge knowledgebase: ${e.message}" }
            false
        }
    }

    suspend fun getExtractionQueue(limit: Int = 200): KbExtractionQueueResponse {
        val grpc = kbQueueGrpc ?: run {
            logger.warn { "KB queue gRPC client not wired" }
            return KbExtractionQueueResponse(items = emptyList(), stats = KbQueueStats())
        }
        return try {
            val proto = grpc.listQueue(limit)
            KbExtractionQueueResponse(
                items = proto.itemsList.map {
                    KbQueueItem(
                        taskId = it.taskId,
                        sourceUrn = it.sourceUrn,
                        clientId = it.clientId,
                        projectId = it.projectId.takeIf { s -> s.isNotEmpty() },
                        kind = it.kind.takeIf { s -> s.isNotEmpty() },
                        createdAt = it.createdAt,
                        status = it.status,
                        attempts = it.attempts,
                        priority = it.priority,
                        error = it.error.takeIf { s -> s.isNotEmpty() },
                        lastAttemptAt = it.lastAttemptAt.takeIf { s -> s.isNotEmpty() },
                        workerId = it.workerId.takeIf { s -> s.isNotEmpty() },
                        progressCurrent = it.progressCurrent,
                        progressTotal = it.progressTotal,
                    )
                },
                stats = KbQueueStats(
                    total = proto.stats.total,
                    pending = proto.stats.pending,
                    inProgress = proto.stats.inProgress,
                    failed = proto.stats.failed,
                ),
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch KB extraction queue: ${e.message}" }
            KbExtractionQueueResponse(items = emptyList(), stats = KbQueueStats())
        }
    }

    // -----------------------------------------------------------------------
    // KB Document Upload & Management
    // -----------------------------------------------------------------------

    /**
     * Register a document in KB and trigger extraction/indexing.
     *
     * The binary file is sent along with metadata via multipart form.
     * KB service creates a graph node and ingests the content into RAG.
     */
    suspend fun uploadKbDocument(
        clientId: String,
        projectId: String?,
        filename: String,
        mimeType: String,
        storagePath: String,
        fileBytes: ByteArray,
        title: String? = null,
        description: String? = null,
        category: String = "OTHER",
        tags: List<String> = emptyList(),
        contentHash: String? = null,
    ): PythonKbDocumentDto {
        logger.info { "KB document upload: filename=$filename client=$clientId" }
        val proto = docGrpc().upload(
            clientId = clientId,
            projectId = projectId,
            filename = filename,
            mimeType = mimeType,
            sizeBytes = fileBytes.size.toLong(),
            storagePath = storagePath,
            fileBytes = fileBytes,
            title = title,
            description = description,
            category = category,
            tags = tags,
            contentHash = contentHash,
        )
        return protoToDto(proto)
    }

    /**
     * Register a document already on shared FS (no binary upload).
     * KB reads the file from storagePath on the PVC.
     */
    private fun docGrpc(): KbDocumentGrpcClient =
        kbDocumentGrpc ?: error("KbDocumentGrpcClient not wired — see RpcClientsConfig.getKnowledgeService()")

    suspend fun registerKbDocument(
        clientId: String,
        projectId: String?,
        filename: String,
        mimeType: String,
        sizeBytes: Long,
        storagePath: String,
        title: String? = null,
        description: String? = null,
        category: String = "OTHER",
        tags: List<String> = emptyList(),
        contentHash: String? = null,
    ): PythonKbDocumentDto {
        logger.info { "KB document register: filename=$filename client=$clientId storagePath=$storagePath" }
        val proto = docGrpc().register(
            clientId = clientId,
            projectId = projectId,
            filename = filename,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            storagePath = storagePath,
            title = title,
            description = description,
            category = category,
            tags = tags,
            contentHash = contentHash,
        )
        return protoToDto(proto)
    }

    suspend fun listKbDocuments(clientId: String, projectId: String?): List<PythonKbDocumentDto> =
        try {
            docGrpc().list(clientId, projectId).itemsList.map(::protoToDto)
        } catch (e: Exception) {
            logger.error(e) { "Failed to list KB documents: ${e.message}" }
            emptyList()
        }

    suspend fun getKbDocument(docId: String): PythonKbDocumentDto? =
        try {
            val proto = docGrpc().get(docId)
            if (proto.id.isEmpty()) null else protoToDto(proto)
        } catch (e: Exception) {
            logger.error(e) { "Failed to get KB document $docId: ${e.message}" }
            null
        }

    suspend fun updateKbDocument(
        docId: String,
        title: String? = null,
        description: String? = null,
        category: String? = null,
        tags: List<String>? = null,
    ): PythonKbDocumentDto? =
        try {
            val proto = docGrpc().update(docId, title, description, category, tags)
            if (proto.id.isEmpty()) null else protoToDto(proto)
        } catch (e: Exception) {
            logger.error(e) { "Failed to update KB document $docId: ${e.message}" }
            null
        }

    suspend fun deleteKbDocument(docId: String): Boolean =
        try {
            docGrpc().delete(docId).ok
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete KB document $docId: ${e.message}" }
            false
        }

    suspend fun reindexKbDocument(docId: String): Boolean =
        try {
            docGrpc().reindex(docId).ok
        } catch (e: Exception) {
            logger.error(e) { "Failed to reindex KB document $docId: ${e.message}" }
            false
        }

    private fun grpc(): KbMaintenanceGrpcClient =
        kbMaintenanceGrpc ?: error("KbMaintenanceGrpcClient not wired — see RpcClientsConfig.getKnowledgeService()")

    /**
     * Update groupId on all KB items for a project. Retries with exponential
     * backoff on transient gRPC errors; returns false if all attempts fail
     * so the caller can record a pending-retag state for crash recovery.
     */
    suspend fun retagGroupId(projectId: String, newGroupId: String?): Boolean {
        logger.info { "KB retag-group: projectId=$projectId newGroupId=$newGroupId" }
        var lastException: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                val delayMs = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                logger.info { "KB retag-group retry $attempt/$MAX_RETRIES after ${delayMs}ms (projectId=$projectId)" }
                kotlinx.coroutines.delay(delayMs)
            }
            try {
                val result = grpc().retagGroup(projectId, newGroupId)
                logger.info {
                    "KB retag-group succeeded for projectId=$projectId " +
                        "(graph=${result.graphUpdated}, weaviate=${result.weaviateUpdated})"
                }
                return true
            } catch (e: io.grpc.StatusException) {
                lastException = e
                val code = e.status.code
                logger.warn(e) { "KB retag-group attempt $attempt failed (status=$code): ${e.message}" }
                if (code == io.grpc.Status.Code.INVALID_ARGUMENT || code == io.grpc.Status.Code.NOT_FOUND) {
                    return false // non-retryable
                }
            } catch (e: Exception) {
                lastException = e
                logger.warn(e) { "KB retag-group attempt $attempt failed: ${e.message}" }
            }
        }
        logger.error(lastException) { "KB retag-group failed after $MAX_RETRIES retries for project $projectId" }
        return false
    }

    /**
     * Run one batch of KB maintenance work.
     * Called by KbMaintenanceService during GPU idle time.
     *
     * @return Batch result with next cursor, or null on error.
     */
    suspend fun runMaintenanceBatch(
        maintenanceType: String,
        clientId: String,
        cursor: String?,
        batchSize: Int,
    ): MaintenanceBatchResult? =
        try {
            val proto = grpc().runBatch(maintenanceType, clientId, cursor, batchSize)
            MaintenanceBatchResult(
                completed = proto.completed,
                nextCursor = proto.nextCursor.takeIf { it.isNotEmpty() },
                processed = proto.processed,
                findings = proto.findings,
                fixed = proto.fixed,
                totalEstimate = proto.totalEstimate,
            )
        } catch (e: Exception) {
            logger.warn(e) { "KB maintenance batch error: ${e.message}" }
            null
        }

    /**
     * Result from KB maintenance batch processing.
     */
    @kotlinx.serialization.Serializable
    data class MaintenanceBatchResult(
        val completed: Boolean = false,
        val nextCursor: String? = null,
        val processed: Int = 0,
        val findings: Int = 0,
        val fixed: Int = 0,
        val totalEstimate: Int = 0,
    )

    /**
     * Retag all KB entries from one project to another (for project merge).
     */
    suspend fun retagProjectId(sourceProjectId: String, targetProjectId: String): Boolean =
        try {
            val result = grpc().retagProject(sourceProjectId, targetProjectId)
            logger.info {
                "KB retag-project succeeded: $sourceProjectId → $targetProjectId " +
                    "(graph=${result.graphUpdated}, weaviate=${result.weaviateUpdated})"
            }
            true
        } catch (e: Exception) {
            logger.warn(e) { "KB retag-project failed: ${e.message}" }
            false
        }

    /**
     * Extract text from a file without RAG indexing.
     *
     * Uses the KB service's extraction pipeline (Tika for documents, VLM for images/PDFs)
     * but only returns the extracted text — no graph nodes or RAG chunks are created.
     *
     * Used by AttachmentExtractionService to get text for Qualifier relevance assessment.
     */
    suspend fun extractText(
        filename: String,
        mimeType: String,
        fileBytes: ByteArray,
    ): TextExtractionResult {
        logger.info { "KB extract-text: filename=$filename mime=$mimeType size=${fileBytes.size}" }
        val proto = docGrpc().extractText(filename, mimeType, fileBytes)
        return TextExtractionResult(
            extractedText = proto.text,
            method = proto.method,
            error = proto.error.takeIf { it.isNotEmpty() },
        )
    }

    fun close() {
        client.close()
    }
}

@Serializable
data class TextExtractionResult(
    @SerialName("extracted_text")
    val extractedText: String = "",
    val method: String = "",
    val error: String? = null,
)

// Ingest DTOs moved to gRPC (KbIngestGrpcClient.ingest).
// Retrieval DTOs moved to gRPC (KbRetrieveGrpcClient).

// Traversal + graph DTOs moved to gRPC (KbGraphGrpcClient).
// Purge DTOs moved to gRPC (KbIngestGrpcClient.purge).

// Full-ingest + git / CPG ingest DTOs moved to gRPC (KbIngestGrpcClient).

// KB document DTOs (internal REST models)

@Serializable
data class PythonKbDocumentDto(
    val id: String = "",
    val clientId: String = "",
    val projectId: String? = null,
    val filename: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val storagePath: String = "",
    val state: String = "UPLOADED",
    val category: String = "OTHER",
    val title: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val extractedTextPreview: String? = null,
    val pageCount: Int? = null,
    val contentHash: String? = null,
    val sourceUrn: String = "",
    val errorMessage: String? = null,
    val ragChunks: List<String> = emptyList(),
    val uploadedAt: String = "",
    val indexedAt: String? = null,
)

// Document register/update DTOs moved to gRPC (KbDocumentGrpcClient).

// KB extraction queue DTOs (used by IndexingQueueRpcImpl)

@Serializable
data class KbExtractionQueueResponse(
    val items: List<KbQueueItem>,
    val stats: KbQueueStats,
)

@Serializable
data class KbQueueItem(
    @SerialName("task_id") val taskId: String,
    @SerialName("source_urn") val sourceUrn: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("project_id") val projectId: String? = null,
    val kind: String? = null,
    @SerialName("created_at") val createdAt: String,
    val status: String,
    val attempts: Int = 0,
    val priority: Int = 4,
    val error: String? = null,
    @SerialName("last_attempt_at") val lastAttemptAt: String? = null,
    @SerialName("worker_id") val workerId: String? = null,
    @SerialName("progress_current") val progressCurrent: Int = 0,
    @SerialName("progress_total") val progressTotal: Int = 0,
)

@Serializable
data class KbQueueStats(
    val total: Int = 0,
    val pending: Int = 0,
    @SerialName("in_progress") val inProgress: Int = 0,
    val failed: Int = 0,
)
