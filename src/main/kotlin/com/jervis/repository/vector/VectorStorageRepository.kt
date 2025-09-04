package com.jervis.repository.vector

import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.QdrantProperties
import com.jervis.configuration.TimeoutsProperties
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.repository.vector.converter.rag.convertRagDocumentToPayload
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Collections
import io.qdrant.client.grpc.JsonWithInt
import io.qdrant.client.grpc.Points
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
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
    private val qdrantProperties: QdrantProperties,
    private val modelsProperties: ModelsProperties,
    private val timeoutsProperties: TimeoutsProperties,
) {
    private val logger = KotlinLogging.logger {}

    // Connection state management
    private val isConnected = AtomicBoolean(false)
    private val qdrantClientRef = AtomicReference<QdrantClient?>(null)

    // Collection cache to avoid recreating them during runtime
    // Using @Volatile HashMap since writes only happen at startup, and the rest are read-only
    @Volatile
    private var collectionsCache = HashMap<ModelType, String>()

    /**
     * Get dimension for a specific embedding model type from configuration
     */
    private fun getDimensionForModelType(modelType: ModelType): Int {
        val modelList = modelsProperties.models[modelType] ?: emptyList()
        val firstModel =
            modelList.firstOrNull()
                ?: throw IllegalArgumentException("No models configured for type: $modelType")

        return firstModel.dimension
            ?: throw IllegalArgumentException("No dimension configured for model type: $modelType")
    }

    /**
     * Generate a collection name using only embedding type and dimension
     * Format: {embedding_type}_{dimension}
     */
    private fun getCollectionNameForModelType(modelType: ModelType): String {
        val dimension = getDimensionForModelType(modelType)

        val typePrefix =
            when (modelType) {
                ModelType.EMBEDDING_TEXT -> "text"
                ModelType.EMBEDDING_CODE -> "code"
                else -> throw IllegalArgumentException("Unsupported collection type: $modelType")
            }

        return "${typePrefix}_$dimension"
    }

    /**
     * Get collection name from cache, fallback to generation if not cached
     */
    private fun getCachedCollectionName(modelType: ModelType): String =
        collectionsCache[modelType] ?: run {
            logger.warn { "Collection not found in cache for $modelType, generating dynamically" }
            getCollectionNameForModelType(modelType)
        }

    /**
     * Validate that all embedding models of the same type have consistent dimensions
     * This prevents dimension mismatch errors when the same model might have different contexts on different machines
     */
    private fun validateEmbeddingDimensionsConsistency() {
        try {
            val embeddingTypes = listOf(ModelType.EMBEDDING_TEXT, ModelType.EMBEDDING_CODE)

            for (embeddingType in embeddingTypes) {
                val modelList = modelsProperties.models[embeddingType] ?: emptyList()
                if (modelList.isEmpty()) continue

                val dimensions = modelList.mapNotNull { it.dimension }.distinct()
                val modelNames = modelList.map { it.model }.distinct()

                when {
                    dimensions.isEmpty() -> {
                        logger.warn { "No dimensions configured for $embeddingType models: $modelNames" }
                    }

                    dimensions.size > 1 -> {
                        logger.error {
                            "DIMENSION MISMATCH for $embeddingType: Found dimensions $dimensions for models $modelNames. All models of the same type must have the same dimension!"
                        }
                        throw IllegalStateException("Dimension mismatch for $embeddingType: $dimensions")
                    }

                    else -> {
                        val dimension = dimensions.first()
                        logger.info { "Dimension validation OK for $embeddingType: dimension=$dimension, models=$modelNames" }
                    }
                }
            }

            logger.info { "Embedding dimensions validation completed successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to validate embedding dimensions: ${e.message}" }
            throw e
        }
    }

    init {
        logger.info { "Starting Qdrant client initialization (non-blocking)" }
        validateEmbeddingDimensionsConsistency()
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
        logger.debug { "Creating Qdrant client at ${qdrantProperties.host}:${qdrantProperties.port}" }
        val client =
            QdrantClient(
                QdrantGrpcClient
                    .newBuilder(qdrantProperties.host, qdrantProperties.port, false, true)
                    .withTimeout(Duration.ofMinutes(timeoutsProperties.qdrant.operationTimeoutMinutes))
                    .build(),
            )
        // Test the connection
        client.listCollectionsAsync().get()
        return client
    }

    /**
     * Initialize both TEXT and CODE collections once connected and populate the cache
     */
    private suspend fun initializeCollections() {
        try {
            val codeCollectionName = getCollectionNameForModelType(ModelType.EMBEDDING_CODE)
            val textCollectionName = getCollectionNameForModelType(ModelType.EMBEDDING_TEXT)
            val codeDimension = getDimensionForModelType(ModelType.EMBEDDING_CODE)
            val textDimension = getDimensionForModelType(ModelType.EMBEDDING_TEXT)

            createCollectionIfNotExists(codeCollectionName, codeDimension)
            createCollectionIfNotExists(textCollectionName, textDimension)

            // Cache the collection names to avoid recreation during runtime
            collectionsCache[ModelType.EMBEDDING_CODE] = codeCollectionName
            collectionsCache[ModelType.EMBEDDING_TEXT] = textCollectionName

            logger.info {
                "Collections initialized and cached: text=$textCollectionName (dim=$textDimension), code=$codeCollectionName (dim=$codeDimension)"
            }
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
                if (e.status.code != Status.Code.ALREADY_EXISTS) throw e
            } catch (e: Exception) {
                val cause = e.cause
                if (cause is StatusRuntimeException && cause.status.code != Status.Code.ALREADY_EXISTS) throw e
            }
        }
    }

    /**
     * Unified store method: picks a collection by type (EMBEDDING_TEXT / EMBEDDING_CODE)
     */
    suspend fun store(
        collectionType: ModelType,
        ragDocument: RagDocument,
        embedding: List<Float>,
    ): String {
        val collection = getCachedCollectionName(collectionType)
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

        val payload = ragDocument.convertRagDocumentToPayload()

        val point =
            Points.PointStruct
                .newBuilder()
                .setId(
                    Points.PointId
                        .newBuilder()
                        .setUuid(pointId)
                        .build(),
                ).setVectors(vectors)
                .putAllPayload(payload)
                .build()

        val ok =
            executeQdrantOperation("Store Document into $collection") { client ->
                client.upsertAsync(collection, listOf(point)).get()
            } != null

        if (!ok) throw RuntimeException("Failed to store document in Qdrant")
        return pointId
    }

    /**
     * Unified search method: picks a collection by type (EMBEDDING_TEXT / EMBEDDING_CODE)
     */
    suspend fun search(
        collectionType: ModelType,
        query: List<Float>,
        limit: Int = 5,
        filter: Map<String, Any>? = null,
    ): List<Map<String, JsonWithInt.Value>> {
        val collection = getCachedCollectionName(collectionType)
        val dimension = getDimensionForModelType(collectionType)
        val effectiveQuery = query.ifEmpty { List(dimension) { 0.0f } }

        val qdrantFilter =
            if (filter.isNullOrEmpty().not()) createQdrantFilter(filter) else null

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

        val out = mutableListOf<Map<String, JsonWithInt.Value>>()
        for (point in results) {
            out += point.payloadMap
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
