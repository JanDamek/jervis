package com.jervis.repository.vector

import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.WeaviateProperties
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.EmbeddingType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import io.weaviate.client.Config
import io.weaviate.client.WeaviateClient
import io.weaviate.client.base.Result
import io.weaviate.client.v1.filters.Operator
import io.weaviate.client.v1.filters.WhereFilter
import io.weaviate.client.v1.graphql.model.GraphQLResponse
import io.weaviate.client.v1.graphql.query.argument.HybridArgument
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument
import io.weaviate.client.v1.graphql.query.fields.Field
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.stereotype.Repository
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Repository for vector storage operations using Weaviate.
 * This repository handles all vector database operations and communicates only with RagDocument objects.
 * It exposes unified store/search APIs and routes to SemanticText or SemanticCode collections by ModelType.
 *
 * Supports hybrid search (BM25 + vector) for better keyword + semantic matching.
 */
@Repository
class VectorStorageRepository(
    private val weaviateProperties: WeaviateProperties,
    private val modelsProperties: ModelsProperties,
) {
    private val logger = KotlinLogging.logger {}

    // Connection state management
    private val isConnected = AtomicBoolean(false)
    private val weaviateClientRef = AtomicReference<WeaviateClient?>(null)

    /**
     * Generate collection name routing to SemanticCode and SemanticText
     * Note: Weaviate uses PascalCase for class names by convention
     */
    private fun getCollectionNameForModelType(modelTypeEnum: ModelTypeEnum): String =
        when (modelTypeEnum) {
            ModelTypeEnum.EMBEDDING_TEXT -> "SemanticText"
            ModelTypeEnum.EMBEDDING_CODE -> "SemanticCode"
            else -> throw IllegalArgumentException("Unsupported collection type: $modelTypeEnum")
        }

    /**
     * Validate that all embedding models of the same type have consistent dimensions
     */
    private fun validateEmbeddingDimensionsConsistency() {
        try {
            val embeddingTypes = listOf(ModelTypeEnum.EMBEDDING_TEXT, ModelTypeEnum.EMBEDDING_CODE)

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
        logger.info { "Starting Weaviate client initialization (non-blocking)" }
        validateEmbeddingDimensionsConsistency()
        CoroutineScope(Dispatchers.IO).launch {
            attemptConnection()
        }
    }

    /**
     * Attempt to establish connection to Weaviate with resilience patterns
     */
    private suspend fun attemptConnection() {
        try {
            val result = createWeaviateClient()
            weaviateClientRef.set(result)
            isConnected.set(true)
            logger.info { "Weaviate client connected successfully to ${weaviateProperties.host}:${weaviateProperties.port}" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to establish initial connection to Weaviate. Will retry in background." }
        }
    }

    /**
     * Create a new Weaviate client
     */
    private suspend fun createWeaviateClient(): WeaviateClient {
        logger.debug { "Creating Weaviate client at ${weaviateProperties.scheme}://${weaviateProperties.host}:${weaviateProperties.port}" }
        val config = Config(weaviateProperties.scheme, "${weaviateProperties.host}:${weaviateProperties.port}")
        val client = WeaviateClient(config)

        // Test the connection
        val metaResult = CompletableFuture.supplyAsync { client.misc().metaGetter().run() }.await()
        if (metaResult.hasErrors()) {
            throw RuntimeException("Failed to connect to Weaviate: ${metaResult.error.messages.joinToString()}")
        }

        logger.info { "Weaviate connection test successful: ${metaResult.result.hostname} version ${metaResult.result.version}" }
        return client
    }

    /**
     * Get the current Weaviate client, attempting to reconnect if necessary
     */
    private suspend fun getWeaviateClient(): WeaviateClient? {
        val client = weaviateClientRef.get()
        if (client != null && isConnected.get()) return client
        logger.info { "Attempting to reconnect to Weaviate..." }
        attemptConnection()
        return weaviateClientRef.get()
    }

    /**
     * Execute Weaviate operation with resilience patterns
     */
    private suspend fun <T> executeWeaviateOperation(
        operationName: String,
        operation: suspend (WeaviateClient) -> T,
    ): T? =
        try {
            val client = getWeaviateClient()
            if (client == null) {
                logger.warn { "Weaviate client not available for $operationName" }
                null
            } else {
                operation(client)
            }
        } catch (e: Exception) {
            logger.error(e) { "Weaviate operation failed: $operationName - ${e.message}" }
            null
        }

    @PreDestroy
    fun cleanup() {
        try {
            isConnected.set(false)
            weaviateClientRef.set(null)
            logger.info { "Weaviate client closed successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Error closing Weaviate client: ${e.message}" }
        }
    }

    /**
     * Unified store method: picks a collection by type (EMBEDDING_TEXT / EMBEDDING_CODE)
     */
    suspend fun store(
        collectionType: ModelTypeEnum,
        ragDocument: RagDocument,
        embedding: List<Float>,
    ): String {
        val className = getCollectionNameForModelType(collectionType)
        val objectId = UUID.randomUUID().toString()

        // Convert RagDocument to Weaviate properties
        val properties = mutableMapOf<String, Any>()
        properties["text"] = ragDocument.text
        properties["clientId"] = ragDocument.clientId.toString()
        ragDocument.projectId?.let { properties["projectId"] = it.toString() }
        properties["ragSourceType"] = ragDocument.ragSourceType.name
        properties["branch"] = ragDocument.branch

        // Optional fields
        ragDocument.from?.let { properties["from"] = it }
        ragDocument.subject?.let { properties["subject"] = it }
        ragDocument.timestamp?.let { properties["timestamp"] = it }
        ragDocument.sourceUri?.let { properties["sourceUri"] = it }
        ragDocument.fileName?.let { properties["fileName"] = it }
        ragDocument.chunkId?.let { properties["chunkId"] = it }
        ragDocument.chunkOf?.let { properties["chunkOf"] = it }
        ragDocument.parentRef?.let { properties["parentRef"] = it }

        val result =
            executeWeaviateOperation("Store Document into $className") { client ->
                CompletableFuture
                    .supplyAsync {
                        client
                            .data()
                            .creator()
                            .withClassName(className)
                            .withID(objectId)
                            .withProperties(properties)
                            .withVector(embedding.toTypedArray())
                            .run()
                    }.await()
            }

        if (result == null || result.hasErrors()) {
            val errorMsg = result?.error?.messages?.joinToString() ?: "Unknown error"
            throw RuntimeException("Failed to store document in Weaviate: $errorMsg")
        }

        return objectId
    }

    /**
     * Overloaded store method with automatic EmbeddingType routing
     */
    suspend fun store(
        embeddingType: EmbeddingType,
        ragDocument: RagDocument,
        embedding: List<Float>,
    ): String {
        val modelTypeEnum =
            when (embeddingType) {
                EmbeddingType.EMBEDDING_TEXT -> ModelTypeEnum.EMBEDDING_TEXT
                EmbeddingType.EMBEDDING_CODE -> ModelTypeEnum.EMBEDDING_CODE
            }
        return store(modelTypeEnum, ragDocument, embedding)
    }

    /**
     * Unified search method with hybrid search support (BM25 + vector)
     * Enhanced with ragSourceType and symbolType filtering
     */
    suspend fun search(
        collectionType: ModelTypeEnum,
        query: List<Float>,
        limit: Int = 100,
        minScore: Float = 0.0f,
        projectId: String? = null,
        clientId: String? = null,
        ragSourceType: RagSourceType? = null,
        symbolType: com.jervis.domain.rag.SymbolType? = null,
        filter: Map<String, Any>? = null,
        useHybridSearch: Boolean = weaviateProperties.hybridSearch.enabled,
        queryText: String? = null,
    ): List<Map<String, Any>> {
        val className = getCollectionNameForModelType(collectionType)

        // Build WHERE filter
        val whereFilter =
            buildWhereFilter(
                projectId = projectId,
                clientId = clientId,
                ragSourceType = ragSourceType,
                symbolType = symbolType,
                additionalFilter = filter,
            )

        // Build fields to retrieve
        val fields =
            listOf(
                Field.builder().name("text").build(),
                Field.builder().name("clientId").build(),
                Field.builder().name("projectId").build(),
                Field.builder().name("ragSourceType").build(),
                Field.builder().name("symbolType").build(),
                Field
                    .builder()
                    .name("_additional")
                    .fields(
                        Field.builder().name("id").build(),
                        Field.builder().name("distance").build(),
                        Field.builder().name("score").build(),
                    ).build(),
            )

        val result =
            executeWeaviateOperation("Search in $className") { client ->
                val queryBuilder =
                    client
                        .graphQL()
                        .get()
                        .withClassName(className)
                        .withFields(*fields.toTypedArray())
                        .withLimit(limit)

                // Apply WHERE filter if exists
                whereFilter?.let { queryBuilder.withWhere(it) }

                // Use hybrid search (BM25 + vector) or pure vector search
                if (useHybridSearch && queryText != null) {
                    val alpha = weaviateProperties.hybridSearch.alpha.toFloat()
                    logger.debug { "Using hybrid search with alpha=$alpha (${alpha * 100}% vector, ${(1 - alpha) * 100}% BM25)" }
                    queryBuilder.withHybrid(
                        HybridArgument
                            .builder()
                            .query(queryText)
                            .vector(query.toTypedArray())
                            .alpha(alpha)
                            .build(),
                    )
                } else {
                    // Pure vector search
                    queryBuilder.withNearVector(
                        NearVectorArgument
                            .builder()
                            .vector(query.toTypedArray())
                            .distance(1.0f - minScore) // Convert similarity to distance
                            .build(),
                    )
                }

                CompletableFuture.supplyAsync { queryBuilder.run() }.await()
            }

        if (result == null || result.hasErrors()) {
            logger.error { "Search failed: ${result?.error?.messages?.joinToString()}" }
            return emptyList()
        }

        return parseSearchResults(result)
    }

    /**
     * Build WHERE filter from multiple conditions
     */
    private fun buildWhereFilter(
        projectId: String?,
        clientId: String?,
        ragSourceType: RagSourceType?,
        symbolType: com.jervis.domain.rag.SymbolType?,
        additionalFilter: Map<String, Any>?,
    ): WhereFilter? {
        val conditions = mutableListOf<WhereFilter>()

        // Client ID filter
        clientId?.let {
            conditions.add(
                WhereFilter
                    .builder()
                    .path("clientId")
                    .operator(Operator.Equal)
                    .valueText(it)
                    .build(),
            )
        }

        // Project ID filter
        projectId?.let {
            conditions.add(
                WhereFilter
                    .builder()
                    .path("projectId")
                    .operator(Operator.Equal)
                    .valueText(it)
                    .build(),
            )
        }

        // RAG source type filter
        ragSourceType?.let {
            conditions.add(
                WhereFilter
                    .builder()
                    .path("ragSourceType")
                    .operator(Operator.Equal)
                    .valueText(it.name)
                    .build(),
            )
        }

        // Symbol type filter (note: this field doesn't exist in RagDocument anymore, but kept for compatibility)
        symbolType?.let {
            conditions.add(
                WhereFilter
                    .builder()
                    .path("symbolName")
                    .operator(Operator.Equal)
                    .valueText(it.name)
                    .build(),
            )
        }

        // Additional custom filters
        additionalFilter?.forEach { (key, value) ->
            conditions.add(
                WhereFilter
                    .builder()
                    .path(key)
                    .operator(Operator.Equal)
                    .valueText(value.toString())
                    .build(),
            )
        }

        return when {
            conditions.isEmpty() -> null
            conditions.size == 1 -> conditions.first()
            else -> {
                // Combine with AND
                WhereFilter
                    .builder()
                    .operator(Operator.And)
                    .operands(*conditions.toTypedArray())
                    .build()
            }
        }
    }

    /**
     * Parse GraphQL search results into map format
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseSearchResults(result: Result<GraphQLResponse>): List<Map<String, Any>> {
        val data = result.result?.data as? Map<String, Any> ?: return emptyList()
        val get = data["Get"] as? Map<String, Any> ?: return emptyList()

        // Get the first class results (SemanticText or SemanticCode)
        val classResults = get.values.firstOrNull() as? List<Map<String, Any>> ?: return emptyList()

        return classResults.map { obj ->
            val resultMap = mutableMapOf<String, Any>()

            // Copy all properties
            obj.forEach { (key, value) ->
                if (key != "_additional") {
                    resultMap[key] = value
                }
            }

            // Extract additional metadata
            val additional = obj["_additional"] as? Map<String, Any>
            additional?.let {
                it["id"]?.let { id -> resultMap["_id"] = id }
                it["distance"]?.let { distance ->
                    // Convert distance to score (1 - distance)
                    val dist = (distance as? Number)?.toDouble() ?: 0.0
                    resultMap["_score"] = 1.0 - dist
                }
                it["score"]?.let { score -> resultMap["_score"] = score }
            }

            resultMap
        }
    }
}
