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
import io.ktor.client.statement.bodyAsChannel
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
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
            socketTimeoutMillis = 5 * 60 * 1000L  // 5 min — prevents stuck readUTF8Line on half-open TCP
        }
    }

    private val apiBaseUrl = baseUrl.trimEnd('/') + "/api/v1"

    override suspend fun ingest(request: IngestRequest): IngestResult {
        logger.debug { "Calling knowledgebase ingest: sourceUrn=${request.sourceUrn}" }

        val pythonRequest = PythonIngestRequest(
            clientId = request.clientId.toString(),
            projectId = request.projectId?.toString(),
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

        return try {
            val httpResponse = client.submitFormWithBinaryData(
                url = "$apiBaseUrl/ingest/full",
                formData = formData {
                    append("clientId", request.clientId.toString())
                    append("sourceUrn", request.sourceUrn)
                    append("sourceType", request.sourceType)
                    request.subject?.let { append("subject", it) }
                    append("content", request.content)
                    request.projectId?.let { append("projectId", it.toString()) }
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

            FullIngestResult(
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
        } catch (e: Exception) {
            logger.error(e) { "Failed to ingestFull to knowledgebase: ${e.message}" }
            FullIngestResult(
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

    /**
     * Streaming version of ingestFull that reads NDJSON progress events
     * from the KB service and calls onProgress for each one.
     *
     * When callbackBaseUrl is configured, passes callback URL + taskId + clientId
     * to the KB service. KB then POSTs progress events directly to the Kotlin server
     * (/internal/kb-progress) for real-time push to UI via WebSocket flow.
     * NDJSON progress events are still read but only for the final result.
     */
    suspend fun ingestFullWithProgress(
        request: FullIngestRequest,
        taskId: String = "",
        clientId: String = "",
        onProgress: suspend (message: String, step: String, metadata: Map<String, String>) -> Unit,
    ): FullIngestResult {
        logger.debug { "Calling knowledgebase ingestFull (streaming): sourceUrn=${request.sourceUrn}" }

        val callbackUrl = if (callbackBaseUrl.isNotBlank() && taskId.isNotBlank()) {
            "${callbackBaseUrl.trimEnd('/')}/internal/kb-progress"
        } else ""

        return try {
            val httpResponse = client.submitFormWithBinaryData(
                url = "$apiBaseUrl/ingest/full",
                formData = formData {
                    append("clientId", request.clientId.toString())
                    append("sourceUrn", request.sourceUrn)
                    append("sourceType", request.sourceType)
                    request.subject?.let { append("subject", it) }
                    append("content", request.content)
                    request.projectId?.let { append("projectId", it.toString()) }
                    append("metadata", Json.encodeToString(request.metadata))

                    // Push-based progress callback params
                    if (callbackUrl.isNotBlank()) {
                        append("callbackUrl", callbackUrl)
                        append("taskId", taskId)
                    }

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
            ) {
                accept(ContentType("application", "x-ndjson"))
            }

            if (!httpResponse.status.isSuccess()) {
                val errorBody = httpResponse.bodyAsText()
                throw RuntimeException("KB ingest/full streaming returned ${httpResponse.status}: $errorBody")
            }

            // Stream NDJSON lines as they arrive (like WhisperRestClient SSE pattern)
            // Socket timeout (5 min) prevents stuck readUTF8Line on half-open TCP
            val channel = httpResponse.bodyAsChannel()
            var result: FullIngestResult? = null

            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank()) continue

                try {
                    val jsonElement = Json.parseToJsonElement(line)
                    val obj = jsonElement as? kotlinx.serialization.json.JsonObject ?: continue
                    val type = obj["type"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }

                    when (type) {
                        "progress" -> {
                            // When callback URL is configured, KB events are pushed via POST
                            // to /internal/kb-progress (real-time). NDJSON events arrive
                            // buffered at the end, so skip them to avoid duplicates.
                            if (callbackUrl.isBlank()) {
                                val step = obj["step"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } ?: "unknown"
                                val message = obj["message"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } ?: ""
                                val metadata = obj["metadata"]?.let { el ->
                                    (el as? kotlinx.serialization.json.JsonObject)?.entries?.associate { (k, v) ->
                                        k to ((v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "")
                                    }
                                } ?: emptyMap()

                                onProgress(message, step, metadata)
                            }
                        }

                        "result" -> {
                            val data = obj["data"] ?: continue
                            val parsed: PythonFullIngestResult = Json.decodeFromJsonElement(
                                PythonFullIngestResult.serializer(),
                                data,
                            )
                            result = FullIngestResult(
                                success = parsed.status == "success",
                                chunksCount = parsed.chunksCount,
                                nodesCreated = parsed.nodesCreated,
                                edgesCreated = parsed.edgesCreated,
                                attachmentsProcessed = parsed.attachmentsProcessed,
                                attachmentsFailed = parsed.attachmentsFailed,
                                summary = parsed.summary,
                                entities = parsed.entities,
                                hasActionableContent = parsed.hasActionableContent,
                                suggestedActions = parsed.suggestedActions,
                                hasFutureDeadline = parsed.hasFutureDeadline,
                                suggestedDeadline = parsed.suggestedDeadline,
                                isAssignedToMe = parsed.isAssignedToMe,
                                urgency = parsed.urgency,
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.warn { "Failed to parse NDJSON line: $line — ${e.message}" }
                }
            }

            result ?: throw RuntimeException("No result received from KB ingest/full streaming")
        } catch (e: Exception) {
            logger.error(e) { "Failed to ingestFull (streaming) to knowledgebase: ${e.message}" }
            FullIngestResult(
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

    override suspend fun retrieve(request: RetrievalRequest): EvidencePack {
        logger.debug { "Calling knowledgebase retrieve: query=${request.query}" }

        val pythonRequest = PythonRetrievalRequest(
            query = request.query,
            clientId = request.clientId.toString(),
            projectId = request.projectId?.toString(),
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

    fun close() {
        client.close()
    }
}

// Python API DTOs (internal)

@Serializable
private data class PythonIngestRequest(
    val clientId: String,
    val projectId: String? = null,
    val sourceUrn: String,
    val kind: String = "",
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val observedAt: String,
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
)

@Serializable
data class KbQueueStats(
    val total: Int = 0,
    val pending: Int = 0,
    @SerialName("in_progress") val inProgress: Int = 0,
    val failed: Int = 0,
)
