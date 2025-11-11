package com.jervis.configuration

import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.WeaviateProperties
import com.jervis.domain.model.ModelTypeEnum
import io.weaviate.client.Config
import io.weaviate.client.WeaviateClient
import io.weaviate.client.v1.misc.model.VectorIndexConfig
import io.weaviate.client.v1.schema.model.DataType
import io.weaviate.client.v1.schema.model.Property
import io.weaviate.client.v1.schema.model.Tokenization
import io.weaviate.client.v1.schema.model.WeaviateClass
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Initializes Weaviate schema (collections) on application startup.
 * Creates SemanticText and SemanticCode collections with proper configuration for hybrid search.
 *
 * Includes automatic migration:
 * - Detects schema configuration changes (distance metric, HNSW params, dimensions)
 * - Automatically deletes old collections and vector-dependent MongoDB data
 * - Recreates collections with new configuration
 * - Requires manual re-indexing after migration
 */
@Component
class WeaviateSchemaInitializer(
    private val weaviateProperties: WeaviateProperties,
    private val modelsProperties: ModelsProperties,
    private val migrationService: com.jervis.service.rag.WeaviateMigrationService,
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun initialize() {
        if (!weaviateProperties.enabled) {
            logger.info { "Weaviate is disabled, skipping schema initialization" }
            return
        }

        runBlocking {
            try {
                logger.info { "Initializing Weaviate schema..." }
                val client = createClient()

                // Check if migration is needed
                val migrationNeeded = migrationService.isMigrationNeeded()

                if (migrationNeeded && weaviateProperties.autoMigrate.enabled) {
                    logger.warn { "Schema migration required - performing automatic migration" }
                    migrationService.performMigration(client)
                } else if (migrationNeeded && !weaviateProperties.autoMigrate.enabled) {
                    throw IllegalStateException(
                        "Weaviate schema configuration changed but auto-migration is disabled. " +
                            "Enable weaviate.auto-migrate.enabled or manually migrate the schema.",
                    )
                }

                // Get existing classes after potential migration
                val existingClasses =
                    client
                        .schema()
                        .getter()
                        .run()
                        ?.result
                        ?.classes
                        ?.map { it.className }
                        ?.toSet() ?: emptySet()

                // Create SemanticText collection
                createCollectionIfNotExists(client, existingClasses, "SemanticText", ModelTypeEnum.EMBEDDING_TEXT)

                // Create SemanticCode collection
                createCollectionIfNotExists(client, existingClasses, "SemanticCode", ModelTypeEnum.EMBEDDING_CODE)

                logger.info { "Weaviate schema initialization completed successfully" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to initialize Weaviate schema: ${e.message}" }
                throw e
            }
        }
    }

    private fun createClient(): WeaviateClient {
        val config = Config(weaviateProperties.scheme, "${weaviateProperties.host}:${weaviateProperties.port}")
        return WeaviateClient(config)
    }

    private fun createCollectionIfNotExists(
        client: WeaviateClient,
        existingClasses: Set<String>,
        className: String,
        modelType: ModelTypeEnum,
    ) {
        if (existingClasses.contains(className)) {
            logger.info { "Collection $className already exists, skipping" }
            return
        }

        val dimension = getDimensionForModelType(modelType)
        logger.info { "Creating collection: $className with dimension: $dimension" }

        val properties =
            listOf(
                // Required fields
                Property
                    .builder()
                    .name("text")
                    .dataType(listOf(DataType.TEXT))
                    .tokenization(Tokenization.WORD)
                    .build(),
                Property
                    .builder()
                    .name("clientId")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("ragSourceType")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("branch")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                // Optional scoping
                Property
                    .builder()
                    .name("projectId")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                // Source identification
                Property
                    .builder()
                    .name("from")
                    .dataType(listOf(DataType.TEXT))
                    .tokenization(Tokenization.WORD)
                    .build(),
                Property
                    .builder()
                    .name("subject")
                    .dataType(listOf(DataType.TEXT))
                    .tokenization(Tokenization.WORD)
                    .build(),
                Property
                    .builder()
                    .name("timestamp")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("sourceUri")
                    .dataType(listOf(DataType.TEXT))
                    .tokenization(Tokenization.WORD)
                    .build(),
                // Code-specific fields
                Property
                    .builder()
                    .name("language")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("packageName")
                    .dataType(listOf(DataType.TEXT))
                    .tokenization(Tokenization.WORD)
                    .build(),
                Property
                    .builder()
                    .name("className")
                    .dataType(listOf(DataType.TEXT))
                    .tokenization(Tokenization.WORD)
                    .build(),
                Property
                    .builder()
                    .name("methodName")
                    .dataType(listOf(DataType.TEXT))
                    .tokenization(Tokenization.WORD)
                    .build(),
                Property
                    .builder()
                    .name("symbolName")
                    .dataType(listOf(DataType.TEXT))
                    .tokenization(Tokenization.WORD)
                    .build(),
                Property
                    .builder()
                    .name("lineStart")
                    .dataType(listOf(DataType.INT))
                    .build(),
                Property
                    .builder()
                    .name("lineEnd")
                    .dataType(listOf(DataType.INT))
                    .build(),
                Property
                    .builder()
                    .name("gitCommitHash")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                // File/document fields
                Property
                    .builder()
                    .name("fileName")
                    .dataType(listOf(DataType.TEXT))
                    .tokenization(Tokenization.WORD)
                    .build(),
                Property
                    .builder()
                    .name("contentType")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                // Confluence fields
                Property
                    .builder()
                    .name("confluencePageId")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("confluenceSpaceKey")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                // Chunking fields
                Property
                    .builder()
                    .name("chunkId")
                    .dataType(listOf(DataType.INT))
                    .build(),
                Property
                    .builder()
                    .name("chunkOf")
                    .dataType(listOf(DataType.INT))
                    .build(),
                Property
                    .builder()
                    .name("parentRef")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
            )

        // Configure HNSW vector index for optimal similarity search
        // Using COSINE distance for L2-normalized embeddings (BGE-M3, nomic-embed)
        val vectorIndexConfig =
            VectorIndexConfig
                .builder()
                .distance("cosine") // COSINE distance for normalized vectors
                .ef(128) // Runtime search quality (higher = better recall, slower)
                .efConstruction(256) // Index build quality (higher = better index, slower build)
                .maxConnections(64) // HNSW graph connections (higher = better recall, more memory)
                .dynamicEfMin(100) // Dynamic ef adjustment
                .dynamicEfMax(500)
                .dynamicEfFactor(8)
                .flatSearchCutoff(40000) // Use brute-force for small collections
                .build()

        val weaviateClass =
            WeaviateClass
                .builder()
                .className(className)
                .description("$className collection for hybrid search (BM25 + vector)")
                .vectorizer("none") // We provide our own embeddings
                .vectorIndexConfig(vectorIndexConfig)
                .properties(properties)
                .build()

        val result =
            client
                .schema()
                .classCreator()
                .withClass(weaviateClass)
                .run()

        if (result.hasErrors()) {
            val errorMsg = result.error.messages.joinToString()
            logger.error { "Failed to create collection $className: $errorMsg" }
            throw RuntimeException("Failed to create Weaviate collection: $errorMsg")
        }

        logger.info { "Collection $className created successfully" }
    }

    private fun getDimensionForModelType(modelTypeEnum: ModelTypeEnum): Int {
        val modelList = modelsProperties.models[modelTypeEnum] ?: emptyList()
        val firstModel =
            modelList.firstOrNull()
                ?: throw IllegalArgumentException("No models configured for type: $modelTypeEnum")
        return firstModel.dimension
            ?: throw IllegalArgumentException("No dimension configured for model type: $modelTypeEnum")
    }
}
