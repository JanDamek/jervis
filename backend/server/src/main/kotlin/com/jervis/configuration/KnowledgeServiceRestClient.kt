package com.jervis.configuration

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
) : KnowledgeService {
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
            socketTimeoutMillis = 10 * 60 * 1000L // 10 min — KB embedding can take long when GPU is busy with chat
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

        val pythonRequest = PythonIngestRequest(
            clientId = request.clientId.toString(),
            projectId = request.projectId?.toString(),
            groupId = request.groupId,
            sourceUrn = request.sourceUrn.toString(),
            kind = request.kind,
            content = request.content,
            metadata = request.metadata.mapValues { it.value.toString() },
            observedAt = DateTimeFormatter.ISO_INSTANT.format(request.observedAt),
        )

        return try {
            val response: PythonIngestResult = client.post("$apiBaseUrl/ingest") {
                contentType(ContentType.Application.Json)
                setBody(pythonRequest)
            }.body()

            IngestResult(
                success = response.status == "success",
                summary = "Ingested ${response.chunksCount} chunks, created ${response.nodesCreated} nodes and ${response.edgesCreated} edges",
                ingestedNodes = emptyList(),
                error = if (response.status != "success") response.status else null,
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

        // Retry on transient failures (SocketTimeout, ClosedByteChannel).
        // KB embedding can take long when GPU is busy with chat requests.
        var lastException: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                val delayMs = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1)) // exponential: 5s, 10s
                logger.info { "KB ingestFull retry $attempt/$MAX_RETRIES after ${delayMs}ms (sourceUrn=${request.sourceUrn})" }
                delay(delayMs)
            }

            try {
                val httpResponse = client.submitFormWithBinaryData(
                    url = "$apiBaseUrl/ingest/full",
                    formData = formData {
                        append("clientId", request.clientId.toString())
                        append("sourceUrn", request.sourceUrn)
                        append("sourceType", request.sourceType.sourceKey)
                        request.subject?.let { append("subject", it) }
                        append("content", request.content)
                        request.projectId?.let { append("projectId", it.toString()) }
                        request.groupId?.let { append("groupId", it) }
                        append("metadata", Json.encodeToString(request.metadata))

                        // Add attachments
                        request.attachments.forEach { attachment ->
                            append(
                                "attachments",
                                attachment.data,
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"${attachment.filename}\"")
                                    attachment.contentType?.let {
                                        append(HttpHeaders.ContentType, it)
                                    }
                                },
                            )
                        }
                    },
                )

                if (!httpResponse.status.isSuccess()) {
                    val errorBody = httpResponse.bodyAsText()
                    throw RuntimeException("KB ingest/full returned ${httpResponse.status}: $errorBody")
                }

                val response: PythonFullIngestResult = httpResponse.body()

                if (attempt > 0) {
                    logger.info { "KB ingestFull succeeded on retry $attempt (sourceUrn=${request.sourceUrn})" }
                }

                return FullIngestResult(
                    success = response.status == "success",
                    chunksCount = response.chunksCount,
                    nodesCreated = response.nodesCreated,
                    edgesCreated = response.edgesCreated,
                    attachmentsProcessed = response.attachmentsProcessed,
                    attachmentsFailed = response.attachmentsFailed,
                    summary = response.summary,
                    entities = response.entities,
                    hasActionableContent = response.hasActionableContent,
                    suggestedActions = response.suggestedActions,
                    hasFutureDeadline = response.hasFutureDeadline,
                    suggestedDeadline = response.suggestedDeadline,
                    isAssignedToMe = response.isAssignedToMe,
                    urgency = response.urgency,
                )
            } catch (e: java.net.SocketTimeoutException) {
                logger.warn { "KB ingestFull socket timeout (attempt $attempt): ${e.message}" }
                lastException = e
            } catch (e: io.ktor.utils.io.ClosedByteChannelException) {
                logger.warn { "KB ingestFull channel closed (attempt $attempt): ${e.message}" }
                lastException = e
            } catch (e: java.io.IOException) {
                logger.warn { "KB ingestFull I/O error (attempt $attempt): ${e.message}" }
                lastException = e
            } catch (e: Exception) {
                // Non-retryable errors (HTTP 4xx, parse errors, etc.)
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

        // All retries exhausted
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
     * KB processes in background and calls back to /internal/kb-done when done.
     * Progress events are pushed to /internal/kb-progress along the way.
     * Returns true if KB accepted the request (HTTP 202).
     */
    suspend fun submitFullIngestAsync(
        request: FullIngestRequest,
        taskId: String,
        clientId: String,
        priority: Int? = null,
        maxTier: String = "NONE",
    ): Boolean {
        val callbackUrl = if (callbackBaseUrl.isNotBlank()) {
            "${callbackBaseUrl.trimEnd('/')}/internal/kb-done"
        } else {
            throw RuntimeException("callbackBaseUrl not configured — cannot use async KB flow")
        }

        logger.info { "KB_ASYNC_SUBMIT: taskId=$taskId sourceUrn=${request.sourceUrn}" }

        val httpResponse = client.submitFormWithBinaryData(
            url = "$apiBaseUrl/ingest/full/async",
            formData = formData {
                append("clientId", request.clientId.toString())
                append("sourceUrn", request.sourceUrn)
                append("sourceType", request.sourceType.sourceKey)
                request.subject?.let { append("subject", it) }
                append("content", request.content)
                request.projectId?.let { append("projectId", it.toString()) }
                request.groupId?.let { append("groupId", it) }
                append("metadata", Json.encodeToString(request.metadata))
                append("callbackUrl", callbackUrl)
                append("taskId", taskId)
                priority?.let { append("priority", it.toString()) }
                append("maxTier", maxTier)

                request.attachments.forEach { attachment ->
                    append(
                        "attachments",
                        attachment.data,
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"${attachment.filename}\"")
                            attachment.contentType?.let {
                                append(HttpHeaders.ContentType, it)
                            }
                        },
                    )
                }
            },
        )

        if (!httpResponse.status.isSuccess()) {
            val errorBody = httpResponse.bodyAsText()
            logger.error { "KB_ASYNC_SUBMIT: failed taskId=$taskId status=${httpResponse.status} body=$errorBody" }
            return false
        }

        logger.info { "KB_ASYNC_SUBMIT: accepted taskId=$taskId" }
        return true
    }

    override suspend fun retrieve(request: RetrievalRequest): EvidencePack {
        logger.debug { "Calling knowledgebase retrieve: query=${request.query}" }

        val pythonRequest = PythonRetrievalRequest(
            query = request.query,
            clientId = request.clientId.toString(),
            projectId = request.projectId?.toString(),
            groupId = request.groupId,
            asOf = request.asOf?.let { DateTimeFormatter.ISO_INSTANT.format(it) },
            minConfidence = request.minConfidence,
            maxResults = request.maxResults,
        )

        return try {
            val response: PythonEvidencePack = client.post("$apiBaseUrl/retrieve") {
                contentType(ContentType.Application.Json)
                setBody(pythonRequest)
            }.body()

            EvidencePack(
                items = response.items.map { item ->
                    EvidenceItem(
                        source = item.sourceUrn,
                        content = item.content,
                        confidence = item.score,
                        metadata = item.metadata.mapValues { (_, v) ->
                            (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                        },
                    )
                },
                summary = "Retrieved ${response.items.size} items",
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

        val pythonRequest = PythonTraversalRequest(
            clientId = clientId.toString(),
            startKey = startKey,
            spec = PythonTraversalSpec(
                direction = spec.direction.name,
                minDepth = 1,
                maxDepth = spec.maxDepth,
                edgeCollection = spec.edgeTypes?.firstOrNull(),
            ),
        )

        return try {
            val response: List<PythonGraphNode> = client.post("$apiBaseUrl/traverse") {
                contentType(ContentType.Application.Json)
                setBody(pythonRequest)
            }.body()

            response.map { node ->
                GraphNode(
                    key = node.key,
                    entityType = node.label,
                    ragChunks = emptyList(),
                    properties = node.properties.mapValues { it.value as Any },
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to traverse knowledgebase: ${e.message}" }
            emptyList()
        }
    }

    override suspend fun ingestGitStructure(request: GitStructureIngestRequest): GitStructureIngestResult {
        logger.debug { "Calling knowledgebase ingestGitStructure: repo=${request.repositoryIdentifier} branch=${request.branch}" }

        val pythonRequest = PythonGitStructureIngestRequest(
            clientId = request.clientId,
            projectId = request.projectId,
            repositoryIdentifier = request.repositoryIdentifier,
            branch = request.branch,
            defaultBranch = request.defaultBranch,
            branches = request.branches.map { b ->
                PythonGitBranchInfo(
                    name = b.name,
                    isDefault = b.isDefault,
                    status = b.status,
                    lastCommitHash = b.lastCommitHash,
                )
            },
            files = request.files.map { f ->
                PythonGitFileInfo(
                    path = f.path,
                    extension = f.extension,
                    language = f.language,
                    sizeBytes = f.sizeBytes,
                )
            },
            classes = request.classes.map { c ->
                PythonGitClassInfo(
                    name = c.name,
                    qualifiedName = c.qualifiedName,
                    filePath = c.filePath,
                    visibility = c.visibility,
                    isInterface = c.isInterface,
                    methods = c.methods,
                )
            },
            fileContents = request.fileContents.map { fc ->
                PythonGitFileContent(
                    path = fc.path,
                    content = fc.content,
                )
            },
            metadata = request.metadata,
        )

        return try {
            val response: PythonGitStructureIngestResult = client.post("$apiBaseUrl/ingest/git-structure") {
                contentType(ContentType.Application.Json)
                setBody(pythonRequest)
            }.body()

            GitStructureIngestResult(
                status = response.status,
                nodesCreated = response.nodesCreated,
                edgesCreated = response.edgesCreated,
                nodesUpdated = response.nodesUpdated,
                repositoryKey = response.repositoryKey,
                branchKey = response.branchKey,
                filesIndexed = response.filesIndexed,
                classesIndexed = response.classesIndexed,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to ingest git structure to knowledgebase: ${e.message}" }
            GitStructureIngestResult(
                status = "error: ${e.message}",
            )
        }
    }

    override suspend fun ingestGitCommits(request: GitCommitIngestRequest): GitCommitIngestResult {
        logger.debug { "Calling knowledgebase ingestGitCommits: repo=${request.repositoryIdentifier} branch=${request.branch} commits=${request.commits.size}" }

        val pythonRequest = PythonGitCommitIngestRequest(
            clientId = request.clientId,
            projectId = request.projectId,
            repositoryIdentifier = request.repositoryIdentifier,
            branch = request.branch,
            commits = request.commits.map { c ->
                PythonGitCommitInfo(
                    hash = c.hash,
                    message = c.message,
                    author = c.author,
                    date = c.date,
                    branch = c.branch,
                    parentHash = c.parentHash,
                    filesModified = c.filesModified,
                    filesCreated = c.filesCreated,
                    filesDeleted = c.filesDeleted,
                )
            },
            diffContent = request.diffContent,
        )

        return try {
            val response: PythonGitCommitIngestResult = client.post("$apiBaseUrl/ingest/git-commits") {
                contentType(ContentType.Application.Json)
                setBody(pythonRequest)
            }.body()

            GitCommitIngestResult(
                status = response.status,
                commitsIngested = response.commitsIngested,
                nodesCreated = response.nodesCreated,
                edgesCreated = response.edgesCreated,
                ragChunks = response.ragChunks,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to ingest git commits to knowledgebase: ${e.message}" }
            GitCommitIngestResult(
                status = "error: ${e.message}",
            )
        }
    }

    override suspend fun ingestCpg(request: CpgIngestRequest): CpgIngestResult {
        logger.debug { "Calling knowledgebase ingestCpg: project=${request.projectId} branch=${request.branch}" }

        val pythonRequest = PythonCpgIngestRequest(
            clientId = request.clientId,
            projectId = request.projectId,
            branch = request.branch,
            workspacePath = request.workspacePath,
        )

        return try {
            val response: PythonCpgIngestResult = client.post("$apiBaseUrl/ingest/cpg") {
                contentType(ContentType.Application.Json)
                setBody(pythonRequest)
            }.body()

            CpgIngestResult(
                status = response.status,
                methodsEnriched = response.methodsEnriched,
                extendsEdges = response.extendsEdges,
                callsEdges = response.callsEdges,
                usesTypeEdges = response.usesTypeEdges,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to ingest CPG to knowledgebase: ${e.message}" }
            CpgIngestResult(
                status = "error: ${e.message}",
            )
        }
    }

    override suspend fun purge(sourceUrn: String): Boolean {
        logger.debug { "Calling knowledgebase purge: sourceUrn=$sourceUrn" }

        return try {
            val response: PythonPurgeResult = client.post("$apiBaseUrl/purge") {
                contentType(ContentType.Application.Json)
                setBody(PythonPurgeRequest(sourceUrn = sourceUrn))
            }.body()

            logger.info {
                "Purge complete: chunks=${response.chunksDeleted} " +
                    "nodes_cleaned=${response.nodesCleaned} edges_cleaned=${response.edgesCleaned} " +
                    "nodes_deleted=${response.nodesDeleted} edges_deleted=${response.edgesDeleted}"
            }
            response.status == "success"
        } catch (e: Exception) {
            logger.error(e) { "Failed to purge knowledgebase: ${e.message}" }
            false
        }
    }

    suspend fun getExtractionQueue(limit: Int = 200): KbExtractionQueueResponse {
        return try {
            client.get("$apiBaseUrl/queue?limit=$limit").body()
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

        val httpResponse = client.submitFormWithBinaryData(
            url = "$apiBaseUrl/documents/upload",
            formData = formData {
                append("clientId", clientId)
                projectId?.let { append("projectId", it) }
                append("filename", filename)
                append("mimeType", mimeType)
                append("storagePath", storagePath)
                title?.let { append("title", it) }
                description?.let { append("description", it) }
                append("category", category)
                if (tags.isNotEmpty()) append("tags", tags.joinToString(","))
                contentHash?.let { append("contentHash", it) }
                append(
                    "file",
                    fileBytes,
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                        append(HttpHeaders.ContentType, mimeType)
                    },
                )
            },
        )

        if (!httpResponse.status.isSuccess()) {
            val errorBody = httpResponse.bodyAsText()
            throw RuntimeException("KB document upload failed ${httpResponse.status}: $errorBody")
        }

        return httpResponse.body()
    }

    /**
     * Register a document already on shared FS (no binary upload).
     * KB reads the file from storagePath on the PVC.
     */
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

        val request = PythonKbDocumentRegisterRequest(
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

        val httpResponse = client.post("$apiBaseUrl/documents/register") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (!httpResponse.status.isSuccess()) {
            val errorBody = httpResponse.bodyAsText()
            throw RuntimeException("KB document register failed ${httpResponse.status}: $errorBody")
        }

        return httpResponse.body()
    }

    suspend fun listKbDocuments(clientId: String, projectId: String?): List<PythonKbDocumentDto> {
        val url = buildString {
            append("$apiBaseUrl/documents?clientId=$clientId")
            if (projectId != null) append("&projectId=$projectId")
        }
        return try {
            client.get(url).body()
        } catch (e: Exception) {
            logger.error(e) { "Failed to list KB documents: ${e.message}" }
            emptyList()
        }
    }

    suspend fun getKbDocument(docId: String): PythonKbDocumentDto? {
        return try {
            client.get("$apiBaseUrl/documents/$docId").body()
        } catch (e: Exception) {
            logger.error(e) { "Failed to get KB document $docId: ${e.message}" }
            null
        }
    }

    suspend fun updateKbDocument(
        docId: String,
        title: String? = null,
        description: String? = null,
        category: String? = null,
        tags: List<String>? = null,
    ): PythonKbDocumentDto? {
        return try {
            val request = PythonKbDocumentUpdateRequest(
                title = title,
                description = description,
                category = category,
                tags = tags,
            )
            val httpResponse = client.post("$apiBaseUrl/documents/$docId") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (!httpResponse.status.isSuccess()) return null
            httpResponse.body()
        } catch (e: Exception) {
            logger.error(e) { "Failed to update KB document $docId: ${e.message}" }
            null
        }
    }

    suspend fun deleteKbDocument(docId: String): Boolean {
        return try {
            val httpResponse = client.post("$apiBaseUrl/documents/$docId/delete") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            httpResponse.status.isSuccess()
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete KB document $docId: ${e.message}" }
            false
        }
    }

    suspend fun reindexKbDocument(docId: String): Boolean {
        return try {
            val httpResponse = client.post("$apiBaseUrl/documents/$docId/reindex") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            httpResponse.status.isSuccess()
        } catch (e: Exception) {
            logger.error(e) { "Failed to reindex KB document $docId: ${e.message}" }
            false
        }
    }

    /**
     * Update groupId on all KB items for a project.
     * Called when a project's group membership changes.
     *
     * Retries with exponential backoff on transient failures (network, 5xx).
     * Returns true on success, false on failure (caller handles crash recovery).
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
                val response = client.post("$apiBaseUrl/retag-group") {
                    contentType(ContentType.Application.Json)
                    setBody(PythonRetagGroupRequest(projectId = projectId, groupId = newGroupId))
                }
                if (response.status.isSuccess()) {
                    logger.info { "KB retag-group succeeded for projectId=$projectId" }
                    return true
                }
                val errorBody = response.bodyAsText()
                logger.warn { "KB retag-group failed: ${response.status} $errorBody" }
                if (response.status.value in 400..499) return false // non-retryable
            } catch (e: Exception) {
                lastException = e
                logger.warn(e) { "KB retag-group attempt $attempt failed for project $projectId: ${e.message}" }
            }
        }
        logger.error(lastException) { "KB retag-group failed after $MAX_RETRIES retries for project $projectId" }
        return false
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

        val httpResponse = client.submitFormWithBinaryData(
            url = "$apiBaseUrl/documents/extract-text",
            formData = formData {
                append("filename", filename)
                append("mimeType", mimeType)
                append(
                    "file",
                    fileBytes,
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                        append(HttpHeaders.ContentType, mimeType)
                    },
                )
            },
        )

        if (!httpResponse.status.isSuccess()) {
            val errorBody = httpResponse.bodyAsText()
            throw RuntimeException("KB extract-text failed ${httpResponse.status}: $errorBody")
        }

        return httpResponse.body()
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

// Python API DTOs (internal)

@Serializable
private data class PythonIngestRequest(
    val clientId: String,
    val projectId: String? = null,
    val groupId: String? = null,
    val sourceUrn: String,
    val kind: String = "",
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val observedAt: String,
    val maxTier: String = "NONE",
)

@Serializable
private data class PythonIngestResult(
    val status: String,
    @SerialName("chunks_count")
    val chunksCount: Int,
    @SerialName("nodes_created")
    val nodesCreated: Int,
    @SerialName("edges_created")
    val edgesCreated: Int,
)

@Serializable
private data class PythonRetrievalRequest(
    val query: String,
    val clientId: String,
    val projectId: String? = null,
    val groupId: String? = null,
    val asOf: String? = null,
    val minConfidence: Double = 0.0,
    val maxResults: Int = 10,
)

@Serializable
private data class PythonEvidenceItem(
    val content: String,
    val score: Double,
    val sourceUrn: String,
    val metadata: Map<String, JsonElement> = emptyMap(),
)

@Serializable
private data class PythonEvidencePack(
    val items: List<PythonEvidenceItem>,
)

@Serializable
private data class PythonTraversalRequest(
    val clientId: String,
    val projectId: String? = null,
    val startKey: String,
    val spec: PythonTraversalSpec,
)

@Serializable
private data class PythonTraversalSpec(
    val direction: String = "OUTBOUND",
    val minDepth: Int = 1,
    val maxDepth: Int = 1,
    val edgeCollection: String? = null,
)

@Serializable
private data class PythonGraphNode(
    val id: String,
    val key: String,
    val label: String,
    val properties: Map<String, String>,
)

@Serializable
private data class PythonPurgeRequest(
    val sourceUrn: String,
)

@Serializable
private data class PythonPurgeResult(
    val status: String,
    @SerialName("chunks_deleted")
    val chunksDeleted: Int = 0,
    @SerialName("nodes_cleaned")
    val nodesCleaned: Int = 0,
    @SerialName("edges_cleaned")
    val edgesCleaned: Int = 0,
    @SerialName("nodes_deleted")
    val nodesDeleted: Int = 0,
    @SerialName("edges_deleted")
    val edgesDeleted: Int = 0,
)

@Serializable
private data class PythonFullIngestResult(
    val status: String,
    @SerialName("chunks_count")
    val chunksCount: Int,
    @SerialName("nodes_created")
    val nodesCreated: Int,
    @SerialName("edges_created")
    val edgesCreated: Int,
    @SerialName("attachments_processed")
    val attachmentsProcessed: Int,
    @SerialName("attachments_failed")
    val attachmentsFailed: Int,
    val summary: String,
    val entities: List<String> = emptyList(),
    val hasActionableContent: Boolean = false,
    val suggestedActions: List<String> = emptyList(),
    // Scheduling hints (three-way routing)
    val hasFutureDeadline: Boolean = false,
    val suggestedDeadline: String? = null,
    val isAssignedToMe: Boolean = false,
    val urgency: String = "normal",
)

@Serializable
private data class PythonGitStructureIngestRequest(
    val clientId: String,
    val projectId: String,
    val repositoryIdentifier: String,
    val branch: String,
    val defaultBranch: String = "main",
    val branches: List<PythonGitBranchInfo> = emptyList(),
    val files: List<PythonGitFileInfo> = emptyList(),
    val classes: List<PythonGitClassInfo> = emptyList(),
    val fileContents: List<PythonGitFileContent> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
private data class PythonGitBranchInfo(
    val name: String,
    val isDefault: Boolean = false,
    val status: String = "active",
    val lastCommitHash: String = "",
)

@Serializable
private data class PythonGitFileInfo(
    val path: String,
    val extension: String = "",
    val language: String = "",
    val sizeBytes: Long = 0,
)

@Serializable
private data class PythonGitClassInfo(
    val name: String,
    val qualifiedName: String = "",
    val filePath: String,
    val visibility: String = "public",
    val isInterface: Boolean = false,
    val methods: List<String> = emptyList(),
)

@Serializable
private data class PythonGitFileContent(
    val path: String,
    val content: String,
)

@Serializable
private data class PythonGitStructureIngestResult(
    val status: String,
    @SerialName("nodes_created")
    val nodesCreated: Int = 0,
    @SerialName("edges_created")
    val edgesCreated: Int = 0,
    @SerialName("nodes_updated")
    val nodesUpdated: Int = 0,
    @SerialName("repository_key")
    val repositoryKey: String = "",
    @SerialName("branch_key")
    val branchKey: String = "",
    @SerialName("files_indexed")
    val filesIndexed: Int = 0,
    @SerialName("classes_indexed")
    val classesIndexed: Int = 0,
)

@Serializable
private data class PythonGitCommitInfo(
    val hash: String,
    val message: String,
    val author: String,
    val date: String,
    val branch: String,
    @SerialName("parent_hash")
    val parentHash: String? = null,
    @SerialName("files_modified")
    val filesModified: List<String> = emptyList(),
    @SerialName("files_created")
    val filesCreated: List<String> = emptyList(),
    @SerialName("files_deleted")
    val filesDeleted: List<String> = emptyList(),
)

@Serializable
private data class PythonGitCommitIngestRequest(
    val clientId: String,
    val projectId: String,
    val repositoryIdentifier: String,
    val branch: String,
    val commits: List<PythonGitCommitInfo>,
    @SerialName("diff_content")
    val diffContent: String? = null,
)

@Serializable
private data class PythonGitCommitIngestResult(
    val status: String,
    @SerialName("commits_ingested")
    val commitsIngested: Int = 0,
    @SerialName("nodes_created")
    val nodesCreated: Int = 0,
    @SerialName("edges_created")
    val edgesCreated: Int = 0,
    @SerialName("rag_chunks")
    val ragChunks: Int = 0,
)

@Serializable
private data class PythonCpgIngestRequest(
    val clientId: String,
    val projectId: String,
    val branch: String,
    val workspacePath: String,
)

@Serializable
private data class PythonCpgIngestResult(
    val status: String,
    @SerialName("methods_enriched")
    val methodsEnriched: Int = 0,
    @SerialName("extends_edges")
    val extendsEdges: Int = 0,
    @SerialName("calls_edges")
    val callsEdges: Int = 0,
    @SerialName("uses_type_edges")
    val usesTypeEdges: Int = 0,
)

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

@Serializable
private data class PythonKbDocumentRegisterRequest(
    val clientId: String,
    val projectId: String? = null,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storagePath: String,
    val title: String? = null,
    val description: String? = null,
    val category: String = "OTHER",
    val tags: List<String> = emptyList(),
    val contentHash: String? = null,
)

@Serializable
private data class PythonRetagGroupRequest(
    val projectId: String,
    val groupId: String? = null,
)

@Serializable
private data class PythonKbDocumentUpdateRequest(
    val title: String? = null,
    val description: String? = null,
    val category: String? = null,
    val tags: List<String>? = null,
)

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
