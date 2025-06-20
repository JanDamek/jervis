package com.jervis.module.vectordb

import com.jervis.core.AppConstants
import com.jervis.rag.Document
import com.jervis.rag.DocumentType
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
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Duration
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
    @SpringValue("\${embedding.unified.dimension:1024}") private val dimension: Int = 1024
) {
    private val logger = KotlinLogging.logger {}
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
            createCollectionIfNotExists(AppConstants.VECTOR_DB_COLLECTION_NAME, dimension)

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

            // Use the constant collection name
            val collectionName = AppConstants.VECTOR_DB_COLLECTION_NAME

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

            logger.debug { "Stored document in $collectionName: ${document.pageContent.take(50)}..." }
            return pointId
        } catch (e: Exception) {
            logger.error(e) { "Error storing document: ${e.message}" }
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
            val effectiveQuery = if (query.isEmpty()) {
                logger.debug { "Empty query vector provided, using default vector" }
                List(dimension) { 0.0f }  // Create a vector of zeros with the correct dimension
            } else {
                query
            }

            // Use the single collection
            val collections = listOf(AppConstants.VECTOR_DB_COLLECTION_NAME)

            logger.debug { "Searching in collection: ${AppConstants.VECTOR_DB_COLLECTION_NAME}" }

            // Convert filter to Qdrant filter that includes both project-specific and global items
            val qdrantFilter = if (filter != null && filter.isNotEmpty()) {
                // Check if filter contains project ID
                if (filter.containsKey("project")) {
                    // Create a filter that matches either the specified project or global items
                    val projectId = filter["project"]

                    // Create a filter with OR condition: project = specified OR project = -1 (global)
                    val projectFilter = Filter.newBuilder()

                    // Add condition for the specified project
                    val projectCondition = FieldCondition.newBuilder()
                        .setKey("project")
                        .setRange(
                            Points.Range.newBuilder()
                                .setGt(0.0)
                                .setLt((projectId as Int).toDouble() + 0.1)
                                .build()
                        )
                        .build()

                    // Add condition for global items (project = -1)
                    val globalCondition = FieldCondition.newBuilder()
                        .setKey("project")
                        .setRange(
                            Points.Range.newBuilder()
                                .setGt(-1.1)
                                .setLt(-0.9)
                                .build()
                        )
                        .build()

                    // Add both conditions with OR logic
                    projectFilter.addShould(
                        Points.Condition.newBuilder()
                            .setField(projectCondition)
                            .build()
                    )

                    projectFilter.addShould(
                        Points.Condition.newBuilder()
                            .setField(globalCondition)
                            .build()
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
                            Points.Condition.newBuilder()
                                .setFilter(projectFilter.build())
                                .build()
                        )

                        // Add other filters as another condition
                        combinedFilter.addMust(
                            Points.Condition.newBuilder()
                                .setFilter(otherFilter)
                                .build()
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
                    val withPayloadSelector = Points.WithPayloadSelector.newBuilder().setEnable(true).build()
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
            // Use the single collection
            val collection = AppConstants.VECTOR_DB_COLLECTION_NAME

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
     * Determine which collection to use for a document
     *
     * @param document The document
     * @return The name of the collection to use
     */
    private fun getCollectionForDocument(document: Document): String {
        // Always use the single collection for all document types
        return AppConstants.VECTOR_DB_COLLECTION_NAME
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
