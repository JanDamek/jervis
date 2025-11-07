package com.jervis.repository.vector

import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.WeaviateProperties
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagDocument
import io.weaviate.client.Config
import io.weaviate.client.WeaviateClient
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

    // ========== Private Implementation ==========

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

        // Build query
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

        // Apply hybrid or pure vector search
        val hybrid = query.hybridSearch
        if (hybrid != null && properties.hybridSearch.enabled) {
            logger.debug {
                "Using hybrid search: alpha=${hybrid.alpha} " +
                    "(${hybrid.alpha * 100}% vector, ${(1 - hybrid.alpha) * 100}% BM25)"
            }
            queryBuilder.withHybrid(
                HybridArgument
                    .builder()
                    .query(hybrid.queryText)
                    .vector(query.embedding.toTypedArray())
                    .alpha(hybrid.alpha.toFloat())
                    .build(),
            )
        } else {
            // Pure vector search
            queryBuilder.withNearVector(
                NearVectorArgument
                    .builder()
                    .vector(query.embedding.toTypedArray())
                    .distance(1.0f - query.minScore)
                    .build(),
            )
        }

        // Execute query
        val result = queryBuilder.run()

        if (result.hasErrors()) {
            logger.error { "Search failed: ${result.errorMessages()}" }
            return emptyList()
        }

        return result.parseSearchResults()
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
