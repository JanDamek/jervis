package com.jervis.repository.vector

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.repository.vector.converter.convertRagDocumentToPayload
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Collections
import io.qdrant.client.grpc.Points
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Repository for vector storage operations using Qdrant.
 * This repository handles all vector database operations and communicates only with RagDocument objects.
 * It exposes unified store/search APIs and routes to TEXT or CODE collections by ModelType.
 */
@Repository
class VectorStorageRepository(
    @Value("\${qdrant.host:localhost}") private val qdrantHost: String,
    @Value("\${qdrant.port:6334}") private val qdrantPort: Int,
) {
    private val logger = KotlinLogging.logger {}

    // Connection state management
    private val isConnected = AtomicBoolean(false)
    private val qdrantClientRef = AtomicReference<QdrantClient?>(null)

    companion object {
        const val SEMANTIC_TEXT_COLLECTION = "semantic_text"
        const val SEMANTIC_CODE_COLLECTION = "semantic_code"
        const val VECTOR_SIZE = 768
    }

    init {
        logger.info { "Starting Qdrant client initialization (non-blocking)" }
        CoroutineScope(Dispatchers.IO).launch {
            attemptConnection()
        }
    }

    /**
     * Attempt to establish connection to Qdrant with resilience patterns
     */
    private suspend fun attemptConnection() {
        try {
            val result = createQdrantClient()
            qdrantClientRef.set(result)
            isConnected.set(true)
            logger.info { "Qdrant client connected successfully" }
            initializeCollections()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to establish initial connection to Qdrant. Will retry in background." }
        }
    }

    /**
     * Create a new Qdrant client
     */
    private suspend fun createQdrantClient(): QdrantClient {
        logger.debug { "Creating Qdrant client at $qdrantHost:$qdrantPort" }
        val client =
            QdrantClient(
                QdrantGrpcClient
                    .newBuilder(qdrantHost, qdrantPort, false, true)
                    .withTimeout(Duration.ofSeconds(15))
                    .build(),
            )
        // Test the connection
        client.listCollectionsAsync().get()
        return client
    }

    /**
     * Initialize both TEXT and CODE collections once connected
     */
    private suspend fun initializeCollections() {
        try {
            createCollectionIfNotExists(SEMANTIC_CODE_COLLECTION, VECTOR_SIZE)
            createCollectionIfNotExists(SEMANTIC_TEXT_COLLECTION, VECTOR_SIZE)

            logger.info { "Collections initialized: text=$SEMANTIC_TEXT_COLLECTION, code=$SEMANTIC_CODE_COLLECTION (dim=$VECTOR_SIZE)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize collections: ${e.message}" }
        }
    }

    /**
     * Get the current Qdrant client, attempting to reconnect if necessary
     */
    private suspend fun getQdrantClient(): QdrantClient? {
        val client = qdrantClientRef.get()
        if (client != null && isConnected.get()) return client
        logger.info { "Attempting to reconnect to Qdrant..." }
        attemptConnection()
        return qdrantClientRef.get()
    }

    /**
     * Execute Qdrant operation with resilience patterns
     */
    private suspend fun <T> executeQdrantOperation(
        operationName: String,
        operation: suspend (QdrantClient) -> T,
    ): T? =
        try {
            val client = getQdrantClient()
            if (client == null) {
                logger.warn { "Qdrant client not available for $operationName" }
                null
            } else {
                operation(client)
            }
        } catch (e: Exception) {
            logger.error(e) { "Qdrant operation failed: $operationName - ${e.message}" }
            null
        }

    @PreDestroy
    fun cleanup() {
        try {
            qdrantClientRef.get()?.close()
            isConnected.set(false)
            qdrantClientRef.set(null)
            logger.info { "Qdrant client closed successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Error closing Qdrant client: ${e.message}" }
        }
    }

    /**
     * Create a collection in Qdrant if it doesn't already exist
     */
    private suspend fun createCollectionIfNotExists(
        collectionName: String,
        dimension: Int,
    ) {
        try {
            val client =
                getQdrantClient() ?: run {
                    logger.warn { "Cannot ensure collection existence - Qdrant client not available" }
                    return
                }
            val existing = client.listCollectionsAsync().get().toList()
            if (existing.contains(collectionName)) {
                logger.debug { "Collection $collectionName already exists" }
                return
            }
            createCollection(collectionName, dimension)
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure collection exists: $collectionName" }
            throw e
        }
    }

    /**
     * Create a collection in the vector database
     */
    private suspend fun createCollection(
        collectionName: String,
        dimension: Int,
    ) {
        val ok: Boolean? =
            executeQdrantOperation("Create Collection") { client ->
                logger.info { "Creating collection: $collectionName with dimension: $dimension" }
                val vectorParams =
                    Collections.VectorParams
                        .newBuilder()
                        .setSize(dimension.toLong())
                        .setDistance(Collections.Distance.Cosine)
                        .build()
                try {
                    client.createCollectionAsync(collectionName, vectorParams).get()
                    true
                } catch (e: StatusRuntimeException) {
                    if (e.status.code == Status.Code.ALREADY_EXISTS) true else throw e
                } catch (e: Exception) {
                    val cause = e.cause
                    if (cause is StatusRuntimeException && cause.status.code == Status.Code.ALREADY_EXISTS) true else throw e
                }
            }
        if (ok != true) throw RuntimeException("Failed to create collection: $collectionName")
    }

    /**
     * Unified store method: picks collection by type (EMBEDDING_TEXT / EMBEDDING_CODE)
     */
    suspend fun store(
        collectionType: ModelType,
        ragDocument: RagDocument,
        embedding: List<Float>,
    ): String {
        val collection =
            when (collectionType) {
                ModelType.EMBEDDING_TEXT -> SEMANTIC_TEXT_COLLECTION
                ModelType.EMBEDDING_CODE -> SEMANTIC_CODE_COLLECTION
                else -> throw IllegalArgumentException("Unsupported collection type: $collectionType")
            }
        val pointId = UUID.randomUUID().toString()

        val vectors =
            Points.Vectors
                .newBuilder()
                .setVector(
                    Points.Vector
                        .newBuilder()
                        .addAllData(embedding)
                        .build(),
                ).build()

        val point =
            Points.PointStruct
                .newBuilder()
                .setId(
                    Points.PointId
                        .newBuilder()
                        .setUuid(pointId)
                        .build(),
                ).setVectors(vectors)
                .putAllPayload(ragDocument.convertRagDocumentToPayload())
                .build()

        val ok =
            executeQdrantOperation("Store Document into $collection") { client ->
                client.upsertAsync(collection, listOf(point)).get()
            } != null

        if (!ok) throw RuntimeException("Failed to store document in Qdrant")
        return pointId
    }

    /**
     * Unified search method: picks collection by type (EMBEDDING_TEXT / EMBEDDING_CODE)
     */
    suspend fun search(
        collectionType: ModelType,
        query: List<Float>,
        limit: Int = 5,
        filter: Map<String, Any>? = null,
    ): List<RagDocument> {
        val collection =
            when (collectionType) {
                ModelType.EMBEDDING_TEXT -> SEMANTIC_TEXT_COLLECTION
                ModelType.EMBEDDING_CODE -> SEMANTIC_CODE_COLLECTION
                else -> throw IllegalArgumentException("Unsupported collection type: $collectionType")
            }

        val effectiveQuery = if (query.isEmpty()) List(VECTOR_SIZE) { 0.0f } else query

        val qdrantFilter =
            if (filter != null && filter.isNotEmpty()) createQdrantFilter(filter) else null

        val searchBuilder =
            Points.SearchPoints
                .newBuilder()
                .setCollectionName(collection)
                .setLimit(limit.toLong())
                .also { b ->
                    effectiveQuery.forEach { b.addVector(it) }
                    b.withPayload =
                        Points.WithPayloadSelector
                            .newBuilder()
                            .setEnable(true)
                            .build()
                    if (qdrantFilter != null) b.filter = qdrantFilter
                }.build()

        val results =
            executeQdrantOperation("Search in $collection") { client ->
                client.searchAsync(searchBuilder).get()
            }?.toList().orEmpty()

        val out = mutableListOf<RagDocument>()
        for (point in results) {
            val payload = point.payloadMap.toMap()
            val pageContent = payload["page_content"]?.stringValue ?: ""
            val meta = mutableMapOf<String, Any>()
            for ((key, v) in payload) {
                if (key != "page_content") {
                    when {
                        v.hasStringValue() -> meta[key] = v.stringValue
                        v.hasIntegerValue() -> meta[key] = v.integerValue
                        v.hasDoubleValue() -> meta[key] = v.doubleValue
                        v.hasBoolValue() -> meta[key] = v.boolValue
                    }
                }
            }

            val documentType =
                when (meta["document_type"] as? String) {
                    "code" -> RagDocumentType.CODE
                    "text" -> RagDocumentType.TEXT
                    "mcp_action" -> RagDocumentType.ACTION
                    "meeting" -> RagDocumentType.MEETING
                    "note" -> RagDocumentType.NOTE
                    "git_history" -> RagDocumentType.GIT_HISTORY
                    "dependency" -> RagDocumentType.DEPENDENCY
                    "todo" -> RagDocumentType.UNKNOWN
                    "class_summary" -> RagDocumentType.CLASS_SUMMARY
                    else -> RagDocumentType.CODE
                }

            val sourceType =
                when (meta["source_type"] as? String) {
                    "file" -> RagSourceType.FILE
                    "git" -> RagSourceType.GIT
                    "agent" -> RagSourceType.AGENT
                    else -> RagSourceType.FILE
                }

            val projectId =
                (meta["projectId"] as? String)?.let { ObjectId(it) }
                    ?: (meta["project"] as? String)?.let { ObjectId(it) }
                    ?: ObjectId.get()

            out += RagDocument(projectId, documentType, sourceType, pageContent)
        }
        return out
    }

    /**
     * Create a Qdrant filter from a map of filter conditions
     */
    private fun createQdrantFilter(filterMap: Map<String, Any>): Points.Filter {
        val filterBuilder = Points.Filter.newBuilder()
        filterMap.forEach { (key, value) ->
            val condition =
                when (value) {
                    is String ->
                        Points.FieldCondition
                            .newBuilder()
                            .setKey(key)
                            .setMatch(Points.Match.newBuilder().setKeyword(value))
                            .build()

                    is Int ->
                        Points.FieldCondition
                            .newBuilder()
                            .setKey(key)
                            .setRange(
                                Points.Range
                                    .newBuilder()
                                    .setGt(0.0)
                                    .setLt(value.toDouble() + 0.1)
                                    .build(),
                            ).build()

                    is Boolean ->
                        Points.FieldCondition
                            .newBuilder()
                            .setKey(key)
                            .setMatch(Points.Match.newBuilder().setKeyword(value.toString()))
                            .build()

                    else -> null
                }
            if (condition != null) {
                filterBuilder.addMust(
                    Points.Condition
                        .newBuilder()
                        .setField(condition)
                        .build(),
                )
            }
        }
        return filterBuilder.build()
    }
}
