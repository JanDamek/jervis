package com.jervis.repository.vector

import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentFilter
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.events.SettingsChangeEvent
import com.jervis.service.indexer.EmbeddingService
import com.jervis.service.mcp.McpAction
import com.jervis.service.resilience.DatabaseConnectionManager
import com.jervis.repository.vector.converter.convertRagDocumentToPayload
import com.jervis.repository.vector.converter.convertRagDocumentToProperties
import com.jervis.repository.vector.converter.toQdrantPayload
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Collections
import io.qdrant.client.grpc.Points
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Data class to pair a document with its similarity score
 */
data class ScoredRagDocument(
    val document: RagDocument,
    val score: Float
)

/**
 * Repository for vector storage operations using Qdrant.
 * This repository handles all vector database operations and communicates only with RagDocument objects.
 * It follows the repository pattern and should only be accessed through service layers.
 */
@Repository
class VectorStorageRepository(
    @Value("\${qdrant.host:localhost}") private val qdrantHost: String,
    @Value("\${qdrant.port:6334}") private val qdrantPort: Int,
    private val embeddingService: EmbeddingService,
    private val connectionManager: DatabaseConnectionManager,
) {
    private val logger = KotlinLogging.logger {}

    // Connection state management
    private val isConnected = AtomicBoolean(false)
    private val qdrantClientRef = AtomicReference<QdrantClient?>(null)
    private val circuitBreaker =
        DatabaseConnectionManager.CircuitBreaker(
            DatabaseConnectionManager.ResilienceConfig(
                maxRetries = 5,
                initialDelayMs = 2000,
                maxDelayMs = 60000,
                circuitBreakerFailureThreshold = 3,
                circuitBreakerRecoveryTimeoutMs = 120000,
                healthCheckIntervalMs = 30000,
                connectionTimeoutMs = 15000,
            ),
        )
    private var healthMonitoringJob: Job? = null
    
    // Collection state tracking to avoid unnecessary recreation
    private var lastKnownDimension: Int? = null
    private var lastKnownModelName: String? = null

    /**
     * Sanitize collection name by replacing disallowed characters with underscores
     *
     * @param name The original collection name
     * @return Sanitized collection name
     */
    private fun sanitizeCollectionName(name: String): String = name.replace("/", "_")

    @PostConstruct
    fun initialize() {
        logger.info { "Starting Qdrant client initialization (non-blocking)" }

        // Start connection attempt in background
        CoroutineScope(Dispatchers.IO).launch {
            attemptConnection()
        }
    }

    /**
     * Attempt to establish connection to Qdrant with resilience patterns
     */
    private suspend fun attemptConnection() {
        val result =
            connectionManager.executeWithResilience(
                operationName = "Qdrant Connection",
                config =
                    DatabaseConnectionManager.ResilienceConfig(
                        maxRetries = 10,
                        initialDelayMs = 2000,
                        maxDelayMs = 60000,
                        backoffMultiplier = 1.5,
                    ),
                circuitBreaker = circuitBreaker,
            ) {
                createQdrantClient()
            }

        if (result != null) {
            qdrantClientRef.set(result)
            isConnected.set(true)
            logger.info { "Qdrant client connected successfully" }

            // Initialize collection after successful connection
            initializeCollection()
        } else {
            logger.warn { "Failed to establish initial connection to Qdrant. Will retry in background." }
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
     * Initialize collection after successful connection
     */
    private suspend fun initializeCollection() {
        try {
            val dimension = embeddingService.embeddingDimension
            val modelName = embeddingService.modelName
            val collectionName = sanitizeCollectionName("jervis_${modelName}_dim$dimension")

            createCollectionIfNotExists(collectionName, dimension)
            
            // Update tracking variables after successful initialization
            lastKnownDimension = dimension
            lastKnownModelName = modelName
            
            logger.info { "Collection initialized: $collectionName (tracking: dim=$dimension, model=$modelName)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize collection: ${e.message}" }
        }
    }

    /**
     * Start health monitoring for the Qdrant connection
     */
    private fun startHealthMonitoring() {
        healthMonitoringJob =
            connectionManager.startHealthMonitoring(
                serviceName = "Qdrant",
                intervalMs = 30000,
                healthCheckOperation = {
                    val client = qdrantClientRef.get()
                    if (client != null) {
                        try {
                            client.listCollectionsAsync().get()
                            true
                        } catch (_: Exception) {
                            false
                        }
                    } else {
                        false
                    }
                },
                onHealthChange = { isHealthy ->
                    isConnected.set(isHealthy)
                    if (!isHealthy) {
                        logger.warn { "Qdrant connection lost, attempting to reconnect..." }
                        CoroutineScope(Dispatchers.IO).launch {
                            attemptConnection()
                        }
                    }
                },
            )
    }

    /**
     * Get the current Qdrant client, attempting to reconnect if necessary
     */
    private suspend fun getQdrantClient(): QdrantClient? {
        val client = qdrantClientRef.get()

        if (client != null && isConnected.get()) {
            return client
        }

        // Try to reconnect
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
        connectionManager.executeWithResilience(
            operationName = operationName,
            circuitBreaker = circuitBreaker,
        ) {
            val client = getQdrantClient() ?: throw RuntimeException("Qdrant client not available")
            operation(client)
        }

    @PreDestroy
    fun cleanup() {
        try {
            // Cancel health monitoring
            healthMonitoringJob?.cancel()

            // Close Qdrant client
            val client = qdrantClientRef.get()
            if (client != null) {
                logger.info { "Closing Qdrant client" }
                client.close()
                logger.info { "Qdrant client closed successfully" }
            }

            isConnected.set(false)
            qdrantClientRef.set(null)
        } catch (e: Exception) {
            logger.error(e) { "Error closing Qdrant client: ${e.message}" }
        }
    }

    /**
     * Handle settings changes, particularly for embedding dimension
     */
    @EventListener(SettingsChangeEvent::class)
    fun handleSettingsChangeEvent() {
        logger.info { "Settings changed, checking if embedding dimension or model changed" }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = getQdrantClient()
                if (client == null) {
                    logger.warn { "Cannot handle settings change - Qdrant client not available" }
                    return@launch
                }

                // Get the current dimension and model name from settings
                val currentDimension = embeddingService.embeddingDimension
                val currentModelName = embeddingService.modelName

                // Check if dimension or model name actually changed
                if (lastKnownDimension == currentDimension && lastKnownModelName == currentModelName) {
                    logger.info { "No change in embedding dimension ($currentDimension) or model ($currentModelName), skipping collection recreation" }
                    return@launch
                }

                logger.info { "Detected change - Previous: dim=${lastKnownDimension}, model=${lastKnownModelName} | Current: dim=${currentDimension}, model=${currentModelName}" }

                // Check if the collection exists and recreate it with new settings
                val collections = client.listCollectionsAsync().get().toList()
                val newCollectionName = sanitizeCollectionName("jervis_${currentModelName}_dim$currentDimension")
                val oldCollectionName = if (lastKnownDimension != null && lastKnownModelName != null) {
                    sanitizeCollectionName("jervis_${lastKnownModelName}_dim$lastKnownDimension")
                } else null

                // Delete old collection if it exists and is different from the new one
                if (oldCollectionName != null && oldCollectionName != newCollectionName && collections.contains(oldCollectionName)) {
                    try {
                        logger.info { "Deleting old collection: $oldCollectionName" }
                        client.deleteCollectionAsync(oldCollectionName).get()
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to delete old collection $oldCollectionName: ${e.message}" }
                    }
                }

                // Create new collection if it doesn't exist
                if (!collections.contains(newCollectionName)) {
                    logger.info { "Recreating collection with new dimension: $currentDimension" }
                    try {
                        createCollection(newCollectionName, currentDimension)
                        logger.info { "Collection recreated successfully with dimension: $currentDimension" }
                    } catch (e: Exception) {
                        logger.error(e) { "Error recreating collection: ${e.message}" }
                        return@launch
                    }
                }

                // Update tracking variables after successful change
                lastKnownDimension = currentDimension
                lastKnownModelName = currentModelName
                logger.info { "Updated collection state tracking: dim=$currentDimension, model=$currentModelName" }

            } catch (e: Exception) {
                logger.error(e) { "Error handling settings change event: ${e.message}" }
            }
        }
    }

    /**
     * Create a collection in Qdrant if it doesn't already exist
     *
     * @param collectionName The name of the collection to create
     * @param dimension The dimension of the vectors in the collection
     */
    private suspend fun createCollectionIfNotExists(
        collectionName: String,
        dimension: Int,
    ) {
        try {
            val client = getQdrantClient()
            if (client == null) {
                logger.warn { "Cannot ensure collection existence - Qdrant client not available" }
                return
            }
            val collections = client.listCollectionsAsync().get().toList()
            if (collections.contains(collectionName)) {
                logger.info { "Collection $collectionName already exists, skipping creation" }
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
     *
     * @param collectionName The name of the collection to create
     * @param dimension The dimension of the vectors in the collection
     */
    suspend fun createCollection(
        collectionName: String,
        dimension: Int,
    ) {
        val result =
            connectionManager.executeWithResilience(
                operationName = "Create Collection",
                circuitBreaker = circuitBreaker,
            ) {
                val client = getQdrantClient() ?: throw RuntimeException("Qdrant client not available")

                logger.info { "Creating collection: $collectionName with dimension: $dimension" }

                val vectorParams =
                    Collections.VectorParams
                        .newBuilder()
                        .setSize(dimension.toLong())
                        .setDistance(Collections.Distance.Cosine)
                        .build()

                try {
                    client.createCollectionAsync(collectionName, vectorParams).get()
                    logger.info { "Collection $collectionName created successfully" }
                } catch (e: StatusRuntimeException) {
                    if (e.status.code == Status.Code.ALREADY_EXISTS) {
                        logger.info { "Collection $collectionName already exists, skipping creation" }
                    } else {
                        throw e
                    }
                } catch (e: Exception) {
                    // Handle other types of exceptions that might wrap StatusRuntimeException
                    val cause = e.cause
                    if (cause is StatusRuntimeException && cause.status.code == Status.Code.ALREADY_EXISTS) {
                        logger.info { "Collection $collectionName already exists, skipping creation" }
                    } else {
                        throw e
                    }
                }
            }

        if (result == null) {
            logger.error { "Failed to create collection: $collectionName" }
            throw RuntimeException("Failed to create collection: $collectionName")
        }
    }

    /**
     * Store a document with its embedding in the vector database
     *
     * @param ragDocument The document to store
     * @param embedding The embedding vector for the document
     * @return The ID of the stored document
     */
    fun storeDocument(
        ragDocument: RagDocument,
        embedding: List<Float>,
    ): String {
        try {
            // Generate a unique ID for the document
            val pointId = UUID.randomUUID().toString()

            // Use the constant collection name with dimension
            val dimension = embeddingService.embeddingDimension
            val collectionName = sanitizeCollectionName("jervis_${embeddingService.modelName}_dim$dimension")

            // Convert embedding to Qdrant vector
            val vectorsBuilder = Points.Vectors.newBuilder()
            vectorsBuilder.vector =
                Points.Vector
                    .newBuilder()
                    .addAllData(embedding)
                    .build()
            val vectors = vectorsBuilder.build()

            // Create point struct
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

            // Upsert the point into the collection
            runBlocking {
                val result =
                    executeQdrantOperation("Store Document") { client ->
                        client.upsertAsync(collectionName, listOf(point)).get()
                    }

                if (result == null) {
                    throw RuntimeException("Failed to store document in Qdrant")
                }
            }

            logger.debug { "Stored document in $collectionName: ${ragDocument.pageContent.take(50)}..." }
            return pointId
        } catch (e: Exception) {
            logger.error(e) { "Error storing document: ${e.message}" }
            throw e
        }
    }

    /**
     * Store a document with its embedding in the vector database (suspend version)
     *
     * @param ragDocument The document to store
     * @param embedding The embedding vector for the document
     * @return The ID of the stored document
     */
    suspend fun storeDocumentSuspend(
        ragDocument: RagDocument,
        embedding: List<Float>,
    ) {
        try {
            // Generate a unique ID for the document
            val pointId = UUID.randomUUID().toString()

            // Use the constant collection name with dimension
            val dimension = embeddingService.embeddingDimension
            val collectionName = sanitizeCollectionName("jervis_${embeddingService.modelName}_dim$dimension")

            // Convert embedding to Qdrant vector
            val vectorsBuilder = Points.Vectors.newBuilder()
            vectorsBuilder.vector =
                Points.Vector
                    .newBuilder()
                    .addAllData(embedding)
                    .build()
            val vectors = vectorsBuilder.build()

            // Create point struct
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

            // Upsert the point into the collection
            val result =
                executeQdrantOperation("Store Document Suspend") { client ->
                    client.upsertAsync(collectionName, listOf(point)).get()
                }

            if (result == null) {
                throw RuntimeException("Failed to store document in Qdrant")
            }

            logger.debug { "Stored document in $collectionName: ${ragDocument.pageContent.take(50)}..." }
        } catch (e: Exception) {
            logger.error(e) { "Error storing document: ${e.message}" }
            throw e
        }
    }

    /**
     * Store an MCP action and its result in the vector database
     *
     * @param action The MCP action
     * @param result The result of the action
     * @param query The original query
     * @param embedding The embedding vector for the action
     * @param projectId The ID of the project
     * @return The ID of the stored action
     */
    fun storeMcpAction(
        action: McpAction,
        result: String,
        query: String,
        embedding: List<Float>,
        projectId: ObjectId,
    ): String {
        try {
            // Generate a unique ID for the action
            val pointId = UUID.randomUUID().toString()

            // Use the constant collection name with dimension
            val dimension = embeddingService.embeddingDimension
            val collectionName = sanitizeCollectionName("jervis_${embeddingService.modelName}_dim$dimension")

            // Create a document from the action and result
            val content =
                """
                Query: $query

                Action Type: ${action.type}
                Action Content: ${action.content}
                Action Parameters: ${action.parameters}

                Result: $result
                """.trimIndent()

            // Create metadata for the action
            val metadata =
                mutableMapOf<String, Any>(
                    "document_type" to "mcp_action",
                    "action_type" to action.type,
                    "timestamp" to Instant.now().toEpochMilli(),
                    "query" to query,
                )

            metadata["project"] = projectId

            // Add action parameters to metadata
            action.parameters.forEach { (key, value) ->
                metadata["param_$key"] = value.toString()
            }

            // Create document
            val ragDocument =
                RagDocument(
                    projectId,
                    RagDocumentType.ACTION,
                    RagSourceType.AGENT,
                    content,
                )

            // Convert embedding to Qdrant vector
            val vectorsBuilder = Points.Vectors.newBuilder()
            vectorsBuilder.vector =
                Points.Vector
                    .newBuilder()
                    .addAllData(embedding)
                    .build()
            val vectors = vectorsBuilder.build()

            // Create point struct
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

            // Upsert the point into the collection
            runBlocking {
                val result =
                    executeQdrantOperation("Store MCP Action") { client ->
                        client.upsertAsync(collectionName, listOf(point)).get()
                    }

                if (result == null) {
                    throw RuntimeException("Failed to store MCP action in Qdrant")
                }
            }

            logger.debug { "Stored MCP action in $collectionName: ${action.type}" }
            return pointId
        } catch (e: Exception) {
            logger.error(e) { "Error storing MCP action: ${e.message}" }
            throw e
        }
    }

    /**
     * Search for similar documents in the vector database
     *
     * @param query The query embedding
     * @param limit The maximum number of results to return
     * @param filter Optional filter to apply to the search
     * @return A list of documents similar to the query
     */
    suspend fun searchSimilar(
        query: List<Float>,
        limit: Int = 5,
        filter: Map<String, Any>? = null,
    ): List<RagDocument> {
        try {
            logger.debug { "Searching for similar documents with filter: $filter" }

            // Handle empty query vector case by using a default vector
            val effectiveQuery =
                query.ifEmpty {
                    logger.debug { "Empty query vector provided, using default vector" }
                    List(embeddingService.embeddingDimension) { 0.0f } // Create a vector of zeros with the correct dimension
                }

            // Use the single collection with dimension
            val dimension = embeddingService.embeddingDimension
            val collectionName = sanitizeCollectionName("jervis_${embeddingService.modelName}_dim$dimension")
            val collections = listOf(collectionName)

            logger.debug { "Searching in collection: $collectionName" }

            // Convert filter to Qdrant filter that includes both project-specific and global items
            val qdrantFilter =
                if (filter != null && filter.isNotEmpty()) {
                    // Check if filter contains project ID
                    if (filter.containsKey("project")) {
                        // Create a filter that matches either the specified project or global items
                        val projectId = filter["project"]

                        // Create a filter with OR condition: project = specified OR project = -1 (global)
                        val projectFilter = Points.Filter.newBuilder()

                        // Add condition for the specified project
                        val projectCondition =
                            Points.FieldCondition
                                .newBuilder()
                                .setKey("project")
                                .setRange(
                                    Points.Range
                                        .newBuilder()
                                        .setGt(0.0)
                                        .setLt((projectId as Number).toDouble() + 0.1)
                                        .build(),
                                ).build()

                        // Add condition for global items (project = -1)
                        val globalCondition =
                            Points.FieldCondition
                                .newBuilder()
                                .setKey("project")
                                .setRange(
                                    Points.Range
                                        .newBuilder()
                                        .setGt(-1.1)
                                        .setLt(-0.9)
                                        .build(),
                                ).build()

                        // Add both conditions with OR logic
                        projectFilter.addShould(
                            Points.Condition
                                .newBuilder()
                                .setField(projectCondition)
                                .build(),
                        )

                        projectFilter.addShould(
                            Points.Condition
                                .newBuilder()
                                .setField(globalCondition)
                                .build(),
                        )

                        // Create a copy of the filter without the project key
                        val otherFilters = filter.filterKeys { it != "project" }

                        // If there are other filters, combine them with the project filter using AND logic
                        if (otherFilters.isNotEmpty()) {
                            val otherFilter = createQdrantFilter(otherFilters)

                            // Combine filters with AND logic
                            val combinedFilter = Points.Filter.newBuilder()

                            // Add project filter as one condition
                            combinedFilter.addMust(
                                Points.Condition
                                    .newBuilder()
                                    .setFilter(projectFilter.build())
                                    .build(),
                            )

                            // Add other filters as another condition
                            combinedFilter.addMust(
                                Points.Condition
                                    .newBuilder()
                                    .setFilter(otherFilter)
                                    .build(),
                            )

                            combinedFilter.build()
                        } else {
                            // If no other filters, just use the project filter
                            projectFilter.build()
                        }
                    } else {
                        // If no project filter, use the original filter
                        createQdrantFilter(filter)
                    }
                } else {
                    null
                }

            val results = mutableListOf<ScoredRagDocument>()

            // Search each collection
            for (collection in collections) {
                try {
                    logger.debug { "Searching in collection: $collection" }

                    // Build search request
                    val searchBuilder =
                        Points.SearchPoints
                            .newBuilder()
                            .setCollectionName(collection)
                            .setLimit(limit.toLong())

                    // Add vector data
                    for (i in effectiveQuery.indices) {
                        searchBuilder.addVector(effectiveQuery[i])
                    }

                    // Add with payload - include all fields
                    val withPayloadSelector =
                        Points.WithPayloadSelector
                            .newBuilder()
                            .setEnable(true)
                            .build()
                    searchBuilder.withPayload = withPayloadSelector

                    // Add filter if provided
                    if (qdrantFilter != null) {
                        searchBuilder.filter = qdrantFilter
                        logger.debug { "Applied filter to search" }
                    }

                    // Execute search
                    logger.debug { "Executing search in collection: $collection" }
                    val searchResponse =
                        executeQdrantOperation("Search Similar Documents") { client ->
                            client.searchAsync(searchBuilder.build()).get()
                        }?.toList()

                    if (searchResponse == null) {
                        logger.warn { "Failed to search collection: $collection" }
                        continue
                    }

                    logger.debug { "Found ${searchResponse.size} results in collection: $collection" }

                    // Convert search results to documents
                    for (point in searchResponse) {
                        val payload = point.payloadMap.toMap()
                        val pageContent = payload["page_content"]?.stringValue ?: ""

                        // Extract metadata from payload
                        val metadata = mutableMapOf<String, Any>()
                        for ((key, value) in payload) {
                            if (key != "page_content") {
                                when {
                                    value.hasStringValue() -> metadata[key] = value.stringValue
                                    value.hasIntegerValue() -> metadata[key] = value.integerValue
                                    value.hasDoubleValue() -> metadata[key] = value.doubleValue
                                    value.hasBoolValue() -> metadata[key] = value.boolValue
                                    // Handle other types as needed
                                }
                            }
                        }

                        // Add score to metadata
                        metadata["score"] = point.score

                        // Determine document type from metadata or use default
                        val documentType =
                            when (metadata["document_type"] as? String) {
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

                        // Determine source type from metadata or use default
                        val sourceType =
                            when (metadata["source_type"] as? String) {
                                "file" -> RagSourceType.FILE
                                "git" -> RagSourceType.GIT
                                "agent" -> RagSourceType.AGENT
                                else -> RagSourceType.FILE
                            }

                        val document = RagDocument(
                            projectId = metadata["projectId"] as ObjectId,
                            documentType,
                            sourceType,
                            pageContent,
                        )
                        
                        results.add(ScoredRagDocument(document, point.score))
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Error searching collection $collection: ${e.message}" }
                    // Continue with other collections
                }
            }

            logger.debug { "Found ${results.size} total results across all collections" }

            // Sort results by score and limit
            val sortedResults = results.sortedByDescending { it.score }.take(limit)
            logger.debug { "Returning ${sortedResults.size} results after sorting and limiting" }

            return sortedResults.map { it.document }
        } catch (e: Exception) {
            logger.error(e) { "Error searching for similar documents: ${e.message}" }
            return emptyList()
        }
    }

    /**
     * Search for similar documents in the vector database with RagDocumentFilter
     *
     * @param query The query embedding
     * @param filter Optional RagDocumentFilter to apply to the search
     * @param limit The maximum number of results to return
     * @return A list of documents similar to the query
     */
    suspend fun searchSimilar(
        query: List<Float>,
        filter: RagDocumentFilter? = null,
        limit: Int = 5,
    ): List<RagDocument> {
        try {
            logger.debug { "Searching for similar documents with RagDocumentFilter: $filter" }

            // Convert RagDocumentFilter to Map for Qdrant filtering (basic filters only)
            val qdrantFilterMap = filter?.toMap()
            
            // Get more results from Qdrant for application-level filtering
            val searchLimit = if (filter != null) limit * 2 else limit
            val qdrantResults = searchSimilar(query, searchLimit, qdrantFilterMap)
            
            // Apply application-level filtering if filter is provided
            val filteredResults = if (filter != null) {
                // For application-level filtering, we need to simulate scores
                // Since we don't have access to scores in the simple search, we'll use position as a proxy
                qdrantResults.mapIndexed { index, document ->
                    val simulatedScore = 1.0f - (index.toFloat() / qdrantResults.size.toFloat())
                    document to simulatedScore
                }.filter { (document, score) ->
                    filter.matches(document, score)
                }.take(limit)
                .map { it.first }
            } else {
                qdrantResults.take(limit)
            }
            
            logger.debug { "Returning ${filteredResults.size} results after application filtering" }
            return filteredResults
            
        } catch (e: Exception) {
            logger.error(e) { "Error searching for similar documents with filter: ${e.message}" }
            return emptyList()
        }
    }

    /**
     * Convert Map-based filter to RagDocumentFilter for backward compatibility
     */
    private fun convertMapToFilter(filterMap: Map<String, Any>): RagDocumentFilter? {
        return try {
            val projectId = filterMap["project"] as? ObjectId
            val documentType = (filterMap["documentType"] as? String)?.let { 
                try {
                    RagDocumentType.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            val ragSourceType = (filterMap["ragSourceType"] as? String)?.let {
                try {
                    RagSourceType.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            
            if (projectId != null || documentType != null || ragSourceType != null) {
                RagDocumentFilter(
                    projectId = projectId,
                    documentType = documentType,
                    ragSourceType = ragSourceType
                )
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Error converting map to filter: ${e.message}" }
            null
        }
    }

    /**
     * Delete documents from the vector database
     *
     * @param filter Filter to select documents to delete
     * @return The number of documents deleted
     */
    fun deleteDocuments(filter: Map<String, Any>): Int {
        try {
            // Use the single collection with dimension
            val dimension = embeddingService.embeddingDimension
            val collection = sanitizeCollectionName("jervis_${embeddingService.modelName}_dim$dimension")

            // Convert filter to Qdrant filter
            val qdrantFilter = createQdrantFilter(filter)

            var totalDeleted = 0

            try {
                val response =
                    runBlocking {
                        executeQdrantOperation("Delete Documents") { client ->
                            client.deleteAsync(collection, qdrantFilter).get()
                        }
                    }

                if (response != null) {
                    logger.info { "Successfully deleted documents from $collection" }
                    totalDeleted = 1 // Just indicate success
                } else {
                    logger.warn { "Failed to delete documents from collection $collection" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error deleting from collection $collection: ${e.message}" }
            }

            return totalDeleted
        } catch (e: Exception) {
            logger.error(e) { "Error deleting documents: ${e.message}" }
            return 0
        }
    }

    /**
     * Verify that all data for a project has been properly stored in both MongoDB and vectordb
     *
     * @param projectId The ID of the project
     * @return A boolean indicating whether vectordb verification passed
     */
    suspend fun verifyDataStorage(projectId: ObjectId): Boolean {
        logger.info { "Verifying data storage for project $projectId" }

        // Verify vectordb storage
        var vectorDbVerified = false
        try {
            // Create filter for this project
            val filter = mapOf("project" to projectId)

            // Search for documents in vectordb
            val searchResults =
                searchSimilar(
                    query = List(embeddingService.embeddingDimension) { 0.0f }, // Default vector
                    limit = 1,
                    filter = filter,
                )

            // Consider vectordb verification successful if we found at least one document
            vectorDbVerified = searchResults.isNotEmpty()
            logger.info { "Found ${searchResults.size} documents in vectordb for project $projectId" }
        } catch (e: Exception) {
            logger.error(e) { "Error verifying vectordb storage: ${e.message}" }
        }

        logger.info { "Data storage verification results for project $projectId:" }
        logger.info { "  Vectordb verified: $vectorDbVerified" }

        return vectorDbVerified
    }

    /**
     * Create a Qdrant filter from a map of filter conditions
     *
     * @param filterMap The filter conditions
     * @return The Qdrant filter
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
                    // Add other types as needed
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
