package com.jervis.configuration

import com.jervis.common.types.ClientId
import com.jervis.knowledgebase.KnowledgeService
import com.jervis.knowledgebase.model.EvidenceItem
import com.jervis.knowledgebase.model.EvidencePack
import com.jervis.knowledgebase.model.FullIngestRequest
import com.jervis.knowledgebase.model.FullIngestResult
import com.jervis.knowledgebase.model.IngestRequest
import com.jervis.knowledgebase.model.IngestResult
import com.jervis.knowledgebase.model.RetrievalRequest
import com.jervis.knowledgebase.service.graphdb.model.GraphNode
import com.jervis.knowledgebase.service.graphdb.model.TraversalSpec
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
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
            val response: PythonFullIngestResult = client.submitFormWithBinaryData(
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
            ).body()

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
)
