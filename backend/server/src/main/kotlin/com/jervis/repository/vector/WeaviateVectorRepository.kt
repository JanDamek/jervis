package com.jervis.repository.vector

import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.WeaviateProperties
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagDocument
import io.weaviate.client.Config
import io.weaviate.client.WeaviateClient
import io.weaviate.client.v1.filters.Operator
import io.weaviate.client.v1.filters.WhereFilter
import io.weaviate.client.v1.graphql.query.argument.HybridArgument
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument
import io.weaviate.client.v1.graphql.query.argument.WhereArgument
import io.weaviate.client.v1.graphql.query.fields.Field
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Modern Kotlin-style repository for Weaviate vector storage.
 *
 * Features:
 * - Kotlin coroutines and Flow for reactive operations
 * - Type-safe DSL for queries and filters
 * - Result<T> for explicit error handling
 * - Simplified connection management with lazy initialization
 * - Extension functions for clean mapping
 *
 * Replaces the legacy VectorStorageRepository with ~220 fewer lines.
 */
@Repository
class WeaviateVectorRepository(
    private val properties: WeaviateProperties,
    private val modelsProperties: ModelsProperties,
) {
    private val logger = KotlinLogging.logger {}

    // Lazy client initialization - connects on first use
    private val client: WeaviateClient by lazy {
        createClient()
    }

    init {
        logger.info { "Weaviate repository initialized (connection will be lazy)" }
        validateEmbeddingDimensions()
    }

    // ========== Store Operations ==========

    /**
     * Store a document with its embedding in Weaviate.
     * Returns Result<String> with document ID or error.
     */
    suspend fun store(
        collectionType: ModelTypeEnum,
        document: RagDocument,
        embedding: List<Float>,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val className = WeaviateCollections.forModelType(collectionType)
                val objectId = UUID.randomUUID().toString()

                // Validate embedding
                require(embedding.isNotEmpty()) { "Embedding is empty for $className" }
                require(embedding.all { it.isFinite() }) { "Embedding contains non-finite values for $className" }
                expectedDimension(collectionType)?.let { expected ->
                    require(embedding.size == expected) {
                        "Embedding dimension mismatch for $className: expected $expected, got ${embedding.size}"
                    }
                }

                logger.debug {
                    "Storing document in $className: sourceType=${document.ragSourceType}, " +
                        "client=${document.clientId}, project=${document.projectId}, branch=${document.branch}"
                }

                val result =
                    client
                        .data()
                        .creator()
                        .withClassName(className)
                        .withID(objectId)
                        .withProperties(document.toWeaviateProperties())
                        .withVector(embedding.toTypedArray())
                        .run()

                if (result.hasErrors()) {
                    throw WeaviateException("Failed to store document: ${result.errorMessages()}")
                }

                logger.debug { "Document stored successfully: id=$objectId, collection=$className" }
                objectId
            }
        }

    // ========== Search Operations ==========

    /**
     * Search for documents using vector similarity.
     * Returns a Flow of SearchResults for streaming/reactive processing.
     */
    fun search(
        collectionType: ModelTypeEnum,
        query: VectorQuery,
    ): Flow<SearchResult> =
        flow {
            val className = WeaviateCollections.forModelType(collectionType)

            logger.debug {
                "Searching in $className: limit=${query.limit}, filters=${query.filters}, " +
                    "hybrid=${query.hybridSearch != null}"
            }

            val results = executeSearch(className, query)

            logger.info {
                "Search completed: collection=$className, found=${results.size}, " +
                    "filters=${query.filters}"
            }

            results.forEach { emit(it) }
        }.flowOn(Dispatchers.IO)

    /**
     * Search and collect all results immediately (non-streaming).
     * Convenience method for cases where Flow is not needed.
     */
    suspend fun searchAll(
        collectionType: ModelTypeEnum,
        query: VectorQuery,
    ): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val className = WeaviateCollections.forModelType(collectionType)
            executeSearch(className, query)
        }

    // ========== Delete Operations ==========

    /**
     * Delete knowledge fragment by its unique knowledge ID.
     * Returns Result<Boolean> - true if deleted, false if not found.
     */
    suspend fun deleteByKnowledgeId(
        collectionType: ModelTypeEnum,
        knowledgeId: String,
        clientId: String,
    ): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val className = WeaviateCollections.forModelType(collectionType)

                logger.info {
                    "Deleting knowledge fragment: collection=$className, " +
                        "knowledgeId=$knowledgeId, clientId=$clientId"
                }

                // Build filter: knowledgeId AND clientId (security)
                val whereFilter =
                    WhereFilter
                        .builder()
                        .operator(Operator.And)
                        .operands(
                            WhereFilter
                                .builder()
                                .path("knowledgeId")
                                .operator(Operator.Equal)
                                .valueText(knowledgeId)
                                .build(),
                            WhereFilter
                                .builder()
                                .path("clientId")
                                .operator(Operator.Equal)
                                .valueText(clientId)
                                .build(),
                        ).build()

                // First, search for objects matching the filter to get their IDs
                // Use empty embedding for filter-only search
                val query =
                    VectorQuery(
                        embedding = emptyList(),
                        filters =
                            WeaviateFilters(
                                knowledgeId = knowledgeId,
                                clientId = clientId,
                            ),
                        limit = 1000, // Should be enough for knowledge fragments with same ID
                    )

                val results = executeSearchByFilter(className, query)

                if (results.isEmpty()) {
                    logger.info { "No objects found with knowledgeId=$knowledgeId" }
                    false
                } else {
                    // Delete each found object by ID
                    var deletedCount = 0
                    results.forEach { result ->
                        try {
                            val deleteResult =
                                client
                                    .data()
                                    .deleter()
                                    .withClassName(className)
                                    .withID(result.id)
                                    .run()

                            if (deleteResult.hasErrors()) {
                                logger.warn {
                                    "Failed to delete object ${result.id}: ${deleteResult.error.messages}"
                                }
                            } else {
                                deletedCount++
                            }
                        } catch (e: Exception) {
                            logger.warn(e) { "Exception deleting object ${result.id}" }
                        }
                    }

                    logger.info {
                        "Knowledge deletion completed: knowledgeId=$knowledgeId, deleted=$deletedCount/${results.size} objects"
                    }
                    deletedCount > 0
                }
            }
        }

    // ========== Private Implementation ==========

    /**
     * Execute filter-only search (no vector embedding required).
     * Used for operations like deletion by knowledgeId.
     */
    private fun executeSearchByFilter(
        className: String,
        query: VectorQuery,
    ): List<SearchResult> {
        logger.debug { "Executing filter-only search: className=$className, filters=${query.filters}" }

        // Build GraphQL query fields - minimal for deletion
        val fields =
            listOf(
                Field
                    .builder()
                    .name("_additional")
                    .fields(
                        Field.builder().name("id").build(),
                    ).build(),
            )

        val queryBuilder =
            client
                .graphQL()
                .get()
                .withClassName(className)
                .withFields(*fields.toTypedArray())
                .withLimit(query.limit)

        // Apply filters
        query.filters.toWhereFilter()?.let { whereFilter ->
            queryBuilder.withWhere(WhereArgument.builder().filter(whereFilter).build())
        } ?: run {
            logger.warn { "No filters provided for filter-only search" }
            return emptyList()
        }

        // Execute query
        val result = queryBuilder.run()

        if (result.hasErrors()) {
            logger.error { "Filter-only search failed: ${result.errorMessages()}" }
            return emptyList()
        }

        // Parse results - minimal parsing for IDs only
        return result.parseSearchResults()
    }

    private fun createClient(): WeaviateClient {
        logger.info {
            "Creating Weaviate client: ${properties.scheme}://${properties.host}:${properties.port}"
        }

        val config = Config(properties.scheme, "${properties.host}:${properties.port}")
        val client = WeaviateClient(config)

        // Test connection
        val meta = client.misc().metaGetter().run()
        if (meta.hasErrors()) {
            throw WeaviateException("Failed to connect to Weaviate: ${meta.errorMessages()}")
        }

        logger.info {
            "Weaviate connected: host=${meta.result.hostname}, version=${meta.result.version}"
        }

        return client
    }

    private fun executeSearch(
        className: String,
        query: VectorQuery,
    ): List<SearchResult> {
        // Validate embedding
        require(query.embedding.isNotEmpty()) { "Search embedding is empty for $className" }
        require(query.embedding.all { it.isFinite() }) { "Search embedding contains non-finite values for $className" }

        // Validate expected dimension if available
        val expectedDim = when (className) {
            WeaviateCollections.forModelType(com.jervis.domain.model.ModelTypeEnum.EMBEDDING_TEXT) -> expectedDimension(com.jervis.domain.model.ModelTypeEnum.EMBEDDING_TEXT)
            WeaviateCollections.forModelType(com.jervis.domain.model.ModelTypeEnum.EMBEDDING_CODE) -> expectedDimension(com.jervis.domain.model.ModelTypeEnum.EMBEDDING_CODE)
            else -> null
        }
        expectedDim?.let { expected ->
            require(query.embedding.size == expected) {
                "Search embedding dimension mismatch for $className: expected $expected, got ${query.embedding.size}"
            }
        }

        // Build GraphQL query fields
        val fields =
            listOf(
                Field.builder().name("text").build(),
                Field.builder().name("clientId").build(),
                Field.builder().name("projectId").build(),
                Field.builder().name("ragSourceType").build(),
                Field.builder().name("fileName").build(),
                Field.builder().name("branch").build(),
                Field.builder().name("chunkId").build(),
                Field.builder().name("chunkOf").build(),
                Field.builder().name("parentRef").build(),
                Field.builder().name("timestamp").build(),
                Field.builder().name("from").build(),
                Field.builder().name("subject").build(),
                Field
                    .builder()
                    .name("_additional")
                    .fields(
                        Field.builder().name("id").build(),
                        Field.builder().name("distance").build(),
                        Field.builder().name("score").build(),
                    ).build(),
            )

        // Build query with user-specified limit
        // Weaviate will handle score filtering at database level via distance parameter
        val queryBuilder =
            client
                .graphQL()
                .get()
                .withClassName(className)
                .withFields(*fields.toTypedArray())
                .withLimit(query.limit)

        // Apply filters
        query.filters.toWhereFilter()?.let { whereFilter ->
            queryBuilder.withWhere(WhereArgument.builder().filter(whereFilter).build())
        }

        // Log embedding vector details for debugging
        val embFirstFive = query.embedding.take(5).joinToString(", ") { "%.4f".format(it) }
        logger.info {
            "WEAVIATE_SEARCH: className=$className, embeddingSize=${query.embedding.size}, " +
                "embeddingFirst5=[$embFirstFive], embeddingSum=${"%.4f".format(query.embedding.sum())}, " +
                "limit=${query.limit}, minScore=${query.minScore}"
        }

        // Apply hybrid or pure vector search
        val hybrid = query.hybridSearch
        if (hybrid != null && properties.hybridSearch.enabled) {
            // Hybrid search uses certainty/distance for score filtering at database level
            // certainty = score (e.g., minScore=0.15 means certainty >= 0.15)
            // distance = 1.0 - score (e.g., minScore=0.15 means distance <= 0.85)
            val hybridArgBuilder = HybridArgument
                .builder()
                .query(hybrid.queryText)
                .vector(query.embedding.toTypedArray())
                .alpha(hybrid.alpha.toFloat())

            // Add certainty/distance threshold if minScore is specified
            // This makes Weaviate filter results at database level
            if (query.minScore > 0.0f) {
                val builderClass = hybridArgBuilder.javaClass
                // Try certainty first (more intuitive: higher = better)
                try {
                    val certaintyMethod = builderClass.getMethod("certainty", java.lang.Float::class.java)
                    certaintyMethod.invoke(hybridArgBuilder, query.minScore)
                    logger.debug { "Applied certainty threshold: ${query.minScore} (database-level filtering)" }
                } catch (e: NoSuchMethodException) {
                    // Fallback to distance if certainty not available
                    try {
                        val maxDistance = 1.0f - query.minScore
                        val distanceMethod = builderClass.getMethod("distance", java.lang.Float::class.java)
                        distanceMethod.invoke(hybridArgBuilder, maxDistance)
                        logger.debug { "Applied distance threshold: $maxDistance (database-level filtering)" }
                    } catch (e2: NoSuchMethodException) {
                        logger.warn { "Neither certainty() nor distance() available in HybridArgument" }
                    }
                }
            }

            val hybridArg = hybridArgBuilder.build()

            logger.info {
                "WEAVIATE_HYBRID: Executing hybrid search with:\n" +
                    "  query='${hybrid.queryText}'\n" +
                    "  alpha=${hybrid.alpha} (1.0=pure vector, 0.0=pure BM25)\n" +
                    "  minScore=${query.minScore}\n" +
                    "  limit=${query.limit}\n" +
                    "  embeddingProvided=${query.embedding.isNotEmpty()}"
            }

            queryBuilder.withHybrid(hybridArg)
        } else {
            // Pure vector search with COSINE similarity threshold
            // For COSINE distance: 0.0 = identical, 2.0 = opposite
            // minScore is similarity (0-1), so we need: distance = 1.0 - minScore
            // But Weaviate COSINE uses certainty (0-1) not distance
            // certainty = 1 - (distance / 2) = similarity for normalized vectors
            logger.debug {
                "Using pure vector search with COSINE: minScore=${query.minScore}, limit=${query.limit}"
            }

            val nearVectorBuilder = NearVectorArgument
                .builder()
                .vector(query.embedding.toTypedArray())

            // Apply certainty threshold if minScore > 0
            if (query.minScore > 0.0f) {
                nearVectorBuilder.certainty(query.minScore)
            }

            queryBuilder.withNearVector(nearVectorBuilder.build())
        }

        // Execute query
        val result = queryBuilder.run()

        if (result.hasErrors()) {
            logger.error { "Search failed: ${result.errorMessages()}" }
            return emptyList()
        }

        // Parse results - Weaviate already filtered by distance/score at database level
        val parsed = result.parseSearchResults()

        if (parsed.isNotEmpty()) {
            val scores = parsed.take(5).map { "%.3f".format(it.score) }.joinToString(", ")
            val ids = parsed.take(5).map { it.id.takeLast(8) }.joinToString(", ")
            val textPreviews = parsed.take(3).map { it.text.take(50).replace("\n", " ") }
            logger.info {
                "Weaviate returned ${parsed.size} results (filtered by database).\n" +
                    "  Top 5 scores: [$scores]\n" +
                    "  Top 5 IDs (last 8 chars): [$ids]\n" +
                    "  Top 3 text previews: ${textPreviews.joinToString(" | ")}"
            }
        }

        return parsed
    }

    private fun expectedDimension(type: ModelTypeEnum): Int? {
        val modelList = modelsProperties.models[type] ?: emptyList()
        if (modelList.isEmpty()) return null
        val dimensions = modelList.mapNotNull { it.dimension }.distinct()
        return when {
            dimensions.isEmpty() -> null
            dimensions.size > 1 -> null
            else -> dimensions.first()
        }
    }

    private fun validateEmbeddingDimensions() {
        val embeddingTypes = listOf(ModelTypeEnum.EMBEDDING_TEXT, ModelTypeEnum.EMBEDDING_CODE)

        embeddingTypes.forEach { embeddingType ->
            val modelList = modelsProperties.models[embeddingType] ?: emptyList()
            if (modelList.isEmpty()) return@forEach

            val dimensions = modelList.mapNotNull { it.dimension }.distinct()
            val modelNames = modelList.map { it.model }

            when {
                dimensions.isEmpty() -> {
                    logger.warn { "No dimensions configured for $embeddingType models: $modelNames" }
                }

                dimensions.size > 1 -> {
                    val error = "Dimension mismatch for $embeddingType: $dimensions for models $modelNames"
                    logger.error { error }
                    throw IllegalStateException(error)
                }

                else -> {
                    logger.info {
                        "Dimension validation OK for $embeddingType: dimension=${dimensions.first()}, models=$modelNames"
                    }
                }
            }
        }

        logger.info { "Embedding dimensions validation completed" }
    }

    @PreDestroy
    fun cleanup() {
        logger.info { "Weaviate repository cleanup complete" }
    }
}

/**
 * Custom exception for Weaviate operations
 */
class WeaviateException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
