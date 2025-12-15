package com.jervis.rag.internal.repository

import com.jervis.configuration.properties.WeaviateProperties
import io.weaviate.client.Config
import io.weaviate.client.WeaviateClient
import io.weaviate.client.v1.filters.Operator
import io.weaviate.client.v1.filters.WhereFilter
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument
import io.weaviate.client.v1.graphql.query.argument.WhereArgument
import io.weaviate.client.v1.graphql.query.fields.Field
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Weaviate vector store implementation.
 * Direct implementation without abstraction - no need for interface.
 *
 * Security: All queries MUST filter by clientId (per-client isolation).
 * Schema initialization handled by WeaviateSchemaInitializer @PostConstruct.
 */
@Component
internal class WeaviateVectorStore(
    private val properties: WeaviateProperties,
) {
    private val logger = KotlinLogging.logger {}

    private val client: WeaviateClient by lazy {
        WeaviateClient(
            Config(
                properties.scheme,
                "${properties.host}:${properties.port}",
            ),
        ).also {
            logger.info { "Weaviate client ready: ${properties.host}:${properties.port}" }
        }
    }

    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down Weaviate client" }
    }

    suspend fun store(
        document: VectorDocument,
        classNameOverride: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val className = classNameOverride
                logger.debug { "Storing in $className: id=${document.id}" }

                val result =
                    client
                        .data()
                        .creator()
                        .withClassName(className)
                        .withID(document.id)
                        .withProperties(document.metadata)
                        .withVector(document.embedding.toTypedArray())
                        .run()

                if (result.hasErrors()) {
                    throw IllegalStateException("Store failed: ${result.error?.messages?.joinToString()}")
                }

                logger.debug { "Stored successfully: $className/${document.id}" }
                document.id
            }
        }

    suspend fun search(
        query: VectorQuery,
        classNameOverride: String,
    ): Result<List<VectorSearchResult>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val className = classNameOverride
                logger.debug {
                    "Searching $className: " +
                        "limit=${query.limit}, minScore=${query.minScore}"
                }

                val whereFilter = buildWhereFilter(query.filters)
                val fields =
                    arrayOf(
                        Field
                            .builder()
                            .name(
                                "_additional",
                            ).fields(Field.builder().name("id").build(), Field.builder().name("certainty").build())
                            .build(),
                        Field.builder().name("content").build(),
                        Field.builder().name("knowledgeType").build(),
                        Field.builder().name("knowledgeSeverity").build(),
                        Field.builder().name("documentId").build(),
                        Field.builder().name("documentTitle").build(),
                        Field.builder().name("documentLocation").build(),
                        Field.builder().name("chunkIndex").build(),
                        Field.builder().name("totalChunks").build(),
                        Field.builder().name("clientId").build(),
                        Field.builder().name("projectId").build(),
                        Field.builder().name("relatedDocuments").build(),
                    )

                val gqlBuilder =
                    client
                        .graphQL()
                        .get()
                        .withClassName(className)
                        .withFields(*fields)
                        .withLimit(query.limit)

                // Add vector search if embedding provided
                if (query.embedding.isNotEmpty()) {
                    gqlBuilder.withNearVector(
                        NearVectorArgument
                            .builder()
                            .vector(query.embedding.toTypedArray())
                            .certainty(query.minScore)
                            .build(),
                    )
                }

                // Add filters
                whereFilter?.let {
                    gqlBuilder.withWhere(WhereArgument.builder().filter(it).build())
                }

                val result = gqlBuilder.run()

                if (result.hasErrors()) {
                    throw IllegalStateException("Search failed: ${result.error?.messages?.joinToString()}")
                }

                val data = result.result?.data as? Map<*, *> ?: return@runCatching emptyList()
                val get = data["Get"] as? Map<*, *> ?: return@runCatching emptyList()
                val collectionResults = get[className] as? List<*> ?: return@runCatching emptyList()

                val results =
                    collectionResults.mapNotNull { item ->
                        val obj = item as? Map<*, *> ?: return@mapNotNull null
                        val additional = obj["_additional"] as? Map<*, *>
                        val id = additional?.get("id") as? String ?: ""
                        val certainty = additional?.get("certainty") as? Double ?: 0.0

                        VectorSearchResult(
                            id = id,
                            content = obj["content"] as? String ?: "",
                            score = certainty,
                            metadata = extractMetadata(obj),
                        )
                    }

                logger.debug { "Search found ${results.size} results" }
                results
            }
        }

    suspend fun getById(
        id: String,
        classNameOverride: String,
    ): Result<VectorSearchResult?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val className = classNameOverride
                val result =
                    client
                        .data()
                        .objectsGetter()
                        .withClassName(className)
                        .withID(id)
                        .run()

                if (result.hasErrors()) {
                    return@runCatching null
                }

                result.result?.firstOrNull()?.let { obj ->
                    VectorSearchResult(
                        id = obj.id,
                        content = obj.properties["content"]?.toString() ?: "",
                        score = 1.0,
                        metadata = obj.properties,
                    )
                }
            }
        }

    suspend fun delete(
        id: String,
        classNameOverride: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val className = classNameOverride
                val result =
                    client
                        .data()
                        .deleter()
                        .withClassName(className)
                        .withID(id)
                        .run()

                if (result.hasErrors()) {
                    throw IllegalStateException("Delete failed: ${result.error?.messages?.joinToString()}")
                }

                logger.debug { "Deleted: $className/$id" }
            }
        }

    suspend fun deleteByFilter(
        filters: VectorFilters,
        classNameOverride: String,
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val className = classNameOverride
                val whereFilter = buildWhereFilter(filters) ?: return@runCatching 0

                val result =
                    client
                        .batch()
                        .objectsBatchDeleter()
                        .withClassName(className)
                        .withWhere(whereFilter)
                        .run()

                if (result.hasErrors()) {
                    throw IllegalStateException("Batch delete failed: ${result.error?.messages?.joinToString()}")
                }

                // Weaviate batch delete doesn't return deleted count directly
                // We just return 0 as success indicator since hasErrors() already checked
                logger.info { "Deleted objects from $className" }
                0
            }
        }

    private fun buildWhereFilter(filters: VectorFilters): WhereFilter? {
        val conditions = mutableListOf<WhereFilter>()

        // Client ID (required)
        conditions.add(
            WhereFilter
                .builder()
                .path("clientId")
                .operator(Operator.Equal)
                .valueText(filters.clientId.toString())
                .build(),
        )

        // Project ID
        filters.projectId?.let {
            conditions.add(
                WhereFilter
                    .builder()
                    .path("projectId")
                    .operator(Operator.Equal)
                    .valueText(it.toString())
                    .build(),
            )
        }

        return when {
            conditions.isEmpty() -> {
                null
            }

            conditions.size == 1 -> {
                conditions.first()
            }

            else -> {
                WhereFilter
                    .builder()
                    .operator(Operator.And)
                    .operands(*conditions.toTypedArray())
                    .build()
            }
        }
    }

    private fun extractMetadata(obj: Map<*, *>): Map<String, Any> {
        val map = mutableMapOf<String, Any>()

        obj.forEach { (key, value) ->
            if (key != "_additional" && key != "content" && value != null) {
                map[key.toString()] = value
            }
        }

        return map
    }
}
