package com.jervis.module.vectordb

import com.jervis.module.indexer.EmbeddingService
import com.jervis.module.llm.SettingsChangeEvent
import com.jervis.module.mcp.McpAction
import com.jervis.persistence.mongo.ChunkMetadataService
import com.jervis.rag.Document
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Collections.Distance
import io.qdrant.client.grpc.Collections.VectorParams
import io.qdrant.client.grpc.Points
import io.qdrant.client.grpc.Points.FieldCondition
import io.qdrant.client.grpc.Points.Filter
import io.qdrant.client.grpc.Points.Match
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import io.qdrant.client.grpc.JsonWithInt.ListValue as QdrantListValue
import io.qdrant.client.grpc.JsonWithInt.Value as QdrantValue
import org.springframework.beans.factory.annotation.Value as SpringValue

/**
 * Service for interacting with the vector database (Qdrant).
 * This service provides methods for storing, retrieving, and searching vectors.
 */
@Service
class VectorDbService(
    @SpringValue("\${qdrant.host:localhost}") private val qdrantHost: String,
    @SpringValue("\${qdrant.port:6334}") private val qdrantPort: Int,
    private val chunkMetadataService: ChunkMetadataService,
    private val embeddingService: EmbeddingService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Sanitize collection name by replacing disallowed characters with underscores
     * 
     * @param name The original collection name
     * @return Sanitized collection name
     */
    private fun sanitizeCollectionName(name: String): String {
        return name.replace("/", "_")
    }
    private lateinit var qdrantClient: QdrantClient

    @PostConstruct
    fun initialize() {
        try {
            logger.info { "Initializing Qdrant client at $qdrantHost:$qdrantPort" }
            qdrantClient =
                QdrantClient(
                    QdrantGrpcClient
                        .newBuilder(qdrantHost, qdrantPort, false, true)
                        .withTimeout(Duration.ofSeconds(30))
                        .build(),
                )

            // Ensure collection exists
            val dimension = embeddingService.getEmbeddingDimension()
            createCollectionIfNotExists(
                sanitizeCollectionName("jervis_${embeddingService.getModelName()}_dim${dimension}"),
                dimension,
            )

            logger.info { "Qdrant client initialized successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize Qdrant client: ${e.message}" }
            throw RuntimeException("Failed to initialize Qdrant client", e)
        }
    }

    @PreDestroy
    fun cleanup() {
        try {
            if (::qdrantClient.isInitialized) {
                logger.info { "Closing Qdrant client" }
                qdrantClient.close()
                logger.info { "Qdrant client closed successfully" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error closing Qdrant client: ${e.message}" }
        }
    }

    /**
     * Handle settings changes, particularly for embedding dimension
     */
    @EventListener
    fun handleSettingsChangeEvent(event: SettingsChangeEvent) {
        logger.info { "Settings changed, checking if embedding dimension changed" }
        try {
            // Get the current dimension from settings
            val currentDimension = embeddingService.getEmbeddingDimension()

            // Check if the collection exists and has the correct dimension
            val collections = qdrantClient.listCollectionsAsync().get()
            val collectionName = sanitizeCollectionName("jervis_${embeddingService.getModelName()}_dim${currentDimension}")
            if (collections.contains(collectionName)) {
                // Collection exists, but we need to recreate it if the dimension changed
                // Unfortunately, Qdrant doesn't provide a way to get the dimension of an existing collection
                // So we'll recreate it to be safe
                logger.info { "Recreating collection with new dimension: $currentDimension" }
                try {
                    // Delete the existing collection
                    qdrantClient.deleteCollectionAsync(collectionName).get()
                    // Create a new collection with the current dimension
                    createCollection(collectionName, currentDimension)
                    logger.info { "Collection recreated successfully with dimension: $currentDimension" }
                } catch (e: Exception) {
                    logger.error(e) { "Error recreating collection: ${e.message}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling settings change event: ${e.message}" }
        }
    }

    /**
     * Create a collection in Qdrant if it doesn't already exist
     *
     * @param collectionName The name of the collection to create
     * @param dimension The dimension of the vectors in the collection
     */
    private fun createCollectionIfNotExists(
        collectionName: String,
        dimension: Int,
    ) {
        try {
            val collections = qdrantClient.listCollectionsAsync().get()
            if (collections.none { it == collectionName }) {
                createCollection(collectionName, dimension)
            } else {
                logger.info { "Collection $collectionName already exists" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error checking if collection exists: ${e.message}" }
            throw e
        }
    }

    /**
     * Create a collection in the vector database
     *
     * @param collectionName The name of the collection to create
     * @param dimension The dimension of the vectors in the collection
     */
    fun createCollection(
        collectionName: String,
        dimension: Int,
    ) {
        try {
            logger.info { "Creating collection: $collectionName with dimension: $dimension" }

            val vectorParams =
                VectorParams
                    .newBuilder()
                    .setSize(dimension.toLong()) // Int -> Long konverze
                    .setDistance(Distance.Cosine)
                    .build()

            qdrantClient.createCollectionAsync(collectionName, vectorParams)
            logger.info { "Collection $collectionName created successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Error creating collection $collectionName: ${e.message}" }
            throw e
        }
    }

    /**
     * Store a document with its embedding in the vector database
     *
     * @param document The document to store
     * @param embedding The embedding vector for the document
     * @return The ID of the stored document
     */
    fun storeDocument(
        document: Document,
        embedding: List<Float>,
    ): String {
        try {
            // Generate a unique ID for the document
            val pointId = UUID.randomUUID().toString()

            // Use the constant collection name with dimension
            val dimension = embeddingService.getEmbeddingDimension()
            val collectionName = sanitizeCollectionName("jervis_${embeddingService.getModelName()}_dim${dimension}")

            // Convert document metadata to Qdrant payload
            val payload = convertMetadataToPayload(document)

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
                    .putAllPayload(payload)
                    .build()

            // Upsert the point into the collection
            qdrantClient.upsertAsync(collectionName, listOf(point))

            // Store metadata in MongoDB
            runBlocking {
                try {
                    chunkMetadataService.saveChunkMetadata(pointId, document, pointId)
                    logger.debug { "Stored chunk metadata in MongoDB for chunk $pointId" }
                } catch (e: Exception) {
                    logger.error(e) { "Error storing chunk metadata in MongoDB: ${e.message}" }
                    // Continue even if MongoDB storage fails - we don't want to fail the vector storage
                }
            }

            logger.debug { "Stored document in $collectionName: ${document.pageContent.take(50)}..." }
            return pointId
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
        projectId: Long? = null,
    ): String {
        try {
            // Generate a unique ID for the action
            val pointId = UUID.randomUUID().toString()

            // Use the constant collection name with dimension
            val dimension = embeddingService.getEmbeddingDimension()
            val collectionName = sanitizeCollectionName("jervis_${embeddingService.getModelName()}_dim${dimension}")

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

            // Add project ID if provided
            if (projectId != null) {
                metadata["project"] = projectId
            }

            // Add action parameters to metadata
            action.parameters.forEach { (key, value) ->
                metadata["param_$key"] = value.toString()
            }

            // Create document
            val document = Document(content, metadata)

            // Convert document metadata to Qdrant payload
            val payload = convertMetadataToPayload(document)

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
                    .putAllPayload(payload)
                    .build()

            // Upsert the point into the collection
            qdrantClient.upsertAsync(collectionName, listOf(point))

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
    fun searchSimilar(
        query: List<Float>,
        limit: Int = 5,
        filter: Map<String, Any>? = null,
    ): List<Document> {
        try {
            logger.debug { "Searching for similar documents with filter: $filter" }

            // Handle empty query vector case by using a default vector
            val effectiveQuery =
                if (query.isEmpty()) {
                    logger.debug { "Empty query vector provided, using default vector" }
                    List(embeddingService.getEmbeddingDimension()) { 0.0f } // Create a vector of zeros with the correct dimension
                } else {
                    query
                }

            // Use the single collection with dimension
            val dimension = embeddingService.getEmbeddingDimension()
            val collectionName = sanitizeCollectionName("jervis_${embeddingService.getModelName()}_dim${dimension}")
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
                        val projectFilter = Filter.newBuilder()

                        // Add condition for the specified project
                        val projectCondition =
                            FieldCondition
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
                            FieldCondition
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
                            val combinedFilter = Filter.newBuilder()

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

            val results = mutableListOf<Document>()

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
                    val searchResponse = qdrantClient.searchAsync(searchBuilder.build()).get()
                    logger.debug { "Found ${searchResponse.size} results in collection: $collection" }

                    // Convert search results to documents
                    for (point in searchResponse) {
                        val payload = point.payloadMap
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

                        results.add(Document(pageContent, metadata))
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Error searching collection $collection: ${e.message}" }
                    // Continue with other collections
                }
            }

            logger.debug { "Found ${results.size} total results across all collections" }

            // Sort results by score and limit
            val sortedResults = results.sortedByDescending { it.metadata["score"] as Float }.take(limit)
            logger.debug { "Returning ${sortedResults.size} results after sorting and limiting" }

            return sortedResults
        } catch (e: Exception) {
            logger.error(e) { "Error searching for similar documents: ${e.message}" }
            return emptyList()
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
            val dimension = embeddingService.getEmbeddingDimension()
            val collection = sanitizeCollectionName("jervis_${embeddingService.getModelName()}_dim${dimension}")

            // Convert filter to Qdrant filter
            val qdrantFilter = createQdrantFilter(filter)

            var totalDeleted = 0

            try {
                val response = qdrantClient.deleteAsync(collection, qdrantFilter).get()
                logger.info { "Deleted ${response.status} documents from $collection" }
                totalDeleted = 1 // Just indicate success
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
     * Convert document metadata to Qdrant payload
     *
     * @param document The document
     * @return The Qdrant payload
     */
    private fun convertMetadataToPayload(document: Document): Map<String, QdrantValue> {
        val payload = mutableMapOf<String, QdrantValue>()

        // Add page content to payload
        payload["page_content"] =
            QdrantValue
                .newBuilder()
                .setStringValue(document.pageContent)
                .build()

        // Add metadata to payload
        document.metadata.forEach { (key, value) ->
            when (value) {
                is String ->
                    payload[key] =
                        QdrantValue
                            .newBuilder()
                            .setStringValue(value)
                            .build()

                is Int ->
                    payload[key] =
                        QdrantValue
                            .newBuilder()
                            .setIntegerValue(value.toLong())
                            .build()

                is Long ->
                    payload[key] =
                        QdrantValue
                            .newBuilder()
                            .setIntegerValue(value)
                            .build()

                is Float ->
                    payload[key] =
                        QdrantValue
                            .newBuilder()
                            .setDoubleValue(value.toDouble())
                            .build()

                is Double ->
                    payload[key] =
                        QdrantValue
                            .newBuilder()
                            .setDoubleValue(value)
                            .build()

                is Boolean ->
                    payload[key] =
                        QdrantValue
                            .newBuilder()
                            .setBoolValue(value)
                            .build()

                is List<*> -> {
                    if (value.isNotEmpty() && value.all { it is String }) {
                        val listValueBuilder = QdrantListValue.newBuilder()
                        value.forEach { item ->
                            listValueBuilder.addValues(
                                QdrantValue
                                    .newBuilder()
                                    .setStringValue(item as String)
                                    .build(),
                            )
                        }
                        payload[key] =
                            QdrantValue
                                .newBuilder()
                                .setListValue(listValueBuilder.build())
                                .build()
                    }
                }
                // Other types can be added as needed
            }
        }

        return payload
    }

    /**
     * Create a Qdrant filter from a map of filter conditions
     *
     * @param filterMap The filter conditions
     * @return The Qdrant filter
     */
    private fun createQdrantFilter(filterMap: Map<String, Any>): Filter {
        val filterBuilder = Filter.newBuilder()

        filterMap.forEach { (key, value) ->
            val condition =
                when (value) {
                    is String ->
                        FieldCondition
                            .newBuilder()
                            .setKey(key)
                            .setMatch(Match.newBuilder().setKeyword(value))
                            .build()

                    is Int ->
                        FieldCondition
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
                        FieldCondition
                            .newBuilder()
                            .setKey(key)
                            .setMatch(Match.newBuilder().setKeyword(value.toString()))
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
