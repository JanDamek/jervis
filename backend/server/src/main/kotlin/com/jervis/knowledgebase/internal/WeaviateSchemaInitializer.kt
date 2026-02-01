package com.jervis.knowledgebase.internal

import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.WeaviateProperties
import io.weaviate.client.Config
import io.weaviate.client.WeaviateClient
import io.weaviate.client.v1.schema.model.DataType
import io.weaviate.client.v1.schema.model.Property
import io.weaviate.client.v1.schema.model.WeaviateClass
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.stereotype.Component

/**
 * Initializes Weaviate schema at application startup.
 *
 * CRITICAL STARTUP COMPONENT:
 * - Runs SYNCHRONOUSLY at application start (@PostConstruct)
 * - BEFORE schedulers start (@Order(0))
 * - Checks for schema migrations
 * - If migration needed: DELETES old schema + CREATES new schema
 * - Blocks application start until complete
 * - NO PARTIAL STATE - either old schema exists OR new schema created
 *
 * SINGLE EMBEDDING MODEL:
 * - Only ONE collection: Knowledge
 * - No separation between TEXT and CODE
 * - Unified embedding for all content types
 *
 * Schema fields:
 * - content: Text content (BM25 indexed)
 * - knowledgeId: Unique chunk identifier
 * - sourceUrn: Source reference (e.g., "email::123", "confluence::456")
 * - clientId: Client identifier (mandatory - security)
 * - projectId: Project identifier (optional)
 * - nodeKeys: Graph node keys (list)
 * - graphRefs: Graph references (list)
 */
@Component
@Order(0) // Run BEFORE other components (BackgroundEngine has @Order(10))
class WeaviateSchemaInitializer(
    private val weaviateProperties: WeaviateProperties,
    private val modelsProperties: ModelsProperties,
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        private const val KNOWLEDGE_COLLECTION = "Knowledge"

        private val INDEXING_MONGODB_COLLECTIONS =
            listOf(
                "email_messages",
                "jira_issue_index",
                "confluence_pages",
                "git_commits",
                "indexed_links",
                "vector_store_index",
            )

        private val SCHEMA_PROPERTIES =
            listOf(
                Property
                    .builder()
                    .name("content")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("knowledgeId")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("sourceUrn")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("clientId")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("projectId")
                    .dataType(listOf(DataType.TEXT))
                    .build(),
                Property
                    .builder()
                    .name("nodeKeys")
                    .dataType(listOf(DataType.TEXT_ARRAY))
                    .build(),
                Property
                    .builder()
                    .name("graphRefs")
                    .dataType(listOf(DataType.TEXT_ARRAY))
                    .build(),
            )

        private val EXPECTED_FIELD_NAMES = SCHEMA_PROPERTIES.map { it.name }.toSet()
    }

    @PostConstruct
    fun initializeSchema() {
        logger.info { "═══════════════════════════════════════════════════════════" }
        logger.info { "WEAVIATE SCHEMA INITIALIZATION - CRITICAL STARTUP PHASE" }
        logger.info { "═══════════════════════════════════════════════════════════" }

        try {
            val client = createClient()

            // Check migration synchronously using runBlocking
            val migrationNeeded =
                runBlocking {
                    checkMigrationNeeded(client)
                }

            if (migrationNeeded) {
                logger.warn { "⚠️  SCHEMA MIGRATION DETECTED ⚠️" }
                logger.warn { "Old schema incompatible with current configuration" }
                logger.warn { "This will DELETE all existing collections and CREATE new schema" }
                logger.warn { "ALL INDEXED DATA WILL BE LOST!" }
                logger.warn { "" }
                logger.warn { "Press Ctrl+C within 10 seconds to CANCEL migration" }
                logger.warn { "Or wait 10 seconds to continue with migration..." }

                // 10 second countdown
                for (i in 10 downTo 1) {
                    Thread.sleep(1000)
                    logger.warn { "Migration in $i seconds..." }
                }

                logger.warn { "" }
                logger.warn { "Starting DESTRUCTIVE migration NOW..." }

                runBlocking {
                    performMigration(client)
                }

                logger.info { "✅ Migration completed successfully" }
            }

            // Ensure schema exists (creates if not exists)
            runBlocking {
                ensureSchemaExists(client)
            }

            logger.info { "✅ Weaviate schema ready" }
            logger.info { "═══════════════════════════════════════════════════════════" }
        } catch (e: Exception) {
            logger.error(e) { "❌ FATAL: Weaviate schema initialization FAILED" }
            logger.error { "Application cannot start without valid schema" }
            throw IllegalStateException("Weaviate schema initialization failed - application cannot start", e)
        }
    }

    private fun createClient(): WeaviateClient {
        logger.info { "Creating Weaviate client: ${weaviateProperties.host}:${weaviateProperties.port}" }
        return WeaviateClient(
            Config(
                weaviateProperties.scheme,
                "${weaviateProperties.host}:${weaviateProperties.port}",
            ),
        )
    }

    private suspend fun checkMigrationNeeded(client: WeaviateClient): Boolean {
        try {
            val schema =
                client
                    .schema()
                    .getter()
                    .run()
                    ?.result ?: return false
            val classes = schema.classes ?: return false

            val knowledgeClass = classes.find { it.className == KNOWLEDGE_COLLECTION }

            if (knowledgeClass == null) {
                logger.info { "No existing collection - will create fresh schema" }
                return false
            }

            // Check if legacy collections exist (TEXT_COLLECTION, CODE_COLLECTION)
            val hasLegacyCollections = classes.any { it.className == "KnowledgeText" || it.className == "KnowledgeCode" }
            if (hasLegacyCollections) {
                logger.warn { "Legacy TEXT/CODE collections detected - migration required" }
                return true
            }

            // Check if collection needs migration
            return needsMigration(knowledgeClass)
        } catch (e: Exception) {
            logger.error(e) { "Failed to check migration status" }
            throw e
        }
    }

    private fun needsMigration(weaviateClass: WeaviateClass): Boolean {
        // Check schema fields
        val existingFieldNames = weaviateClass.properties?.map { it.name }?.toSet() ?: emptySet()
        val missingFields = EXPECTED_FIELD_NAMES - existingFieldNames
        val extraFields = existingFieldNames - EXPECTED_FIELD_NAMES - setOf("_id") // _id is internal

        if (missingFields.isNotEmpty()) {
            logger.warn { "Schema ${weaviateClass.className} missing fields: $missingFields" }
            return true
        }

        if (extraFields.isNotEmpty()) {
            logger.warn { "Schema ${weaviateClass.className} has obsolete fields: $extraFields (will be removed)" }
            return true
        }

        val vectorIndexConfig = weaviateClass.vectorIndexConfig as? Map<*, *> ?: return false

        // Check distance metric
        val currentDistance = vectorIndexConfig["distance"] as? String
        if (currentDistance != "cosine") {
            logger.info { "Distance metric changed in ${weaviateClass.className}: $currentDistance -> cosine" }
            return true
        }

        // Check HNSW parameters
        val currentEf = (vectorIndexConfig["ef"] as? Number)?.toInt()
        val currentEfConstruction = (vectorIndexConfig["efConstruction"] as? Number)?.toInt()
        val currentMaxConnections = (vectorIndexConfig["maxConnections"] as? Number)?.toInt()

        if (currentEf != 128 || currentEfConstruction != 256 || currentMaxConnections != 64) {
            logger.info { "HNSW parameters changed in ${weaviateClass.className}" }
            return true
        }

        // Check vector dimensions - CRITICAL: different dimension = different embedding model
        val embeddingModel = modelsProperties.models.values.flatten().firstOrNull()
        val expectedDimension = embeddingModel?.dimension

        if (expectedDimension != null) {
            // Weaviate stores dimension in vectorIndexConfig under "dimensions" or in class-level vectorConfig
            val currentDimension = (vectorIndexConfig["dimensions"] as? Number)?.toInt()
                ?: ((weaviateClass.vectorConfig as? Map<*, *>)?.get("dimensions") as? Number)?.toInt()

            if (currentDimension != null && currentDimension != expectedDimension) {
                logger.warn {
                    "Vector dimension mismatch in ${weaviateClass.className}: current=$currentDimension, expected=$expectedDimension. " +
                    "Different dimensions mean different embedding models - FULL REINDEXING REQUIRED!"
                }
                return true
            } else if (currentDimension == null) {
                logger.debug { "Could not detect current vector dimension from Weaviate schema, skipping dimension check" }
            } else {
                logger.info { "Vector dimension verified: $currentDimension (matches configured model)" }
            }
        }

        return false
    }

    private suspend fun performMigration(client: WeaviateClient) {
        logger.warn { "Starting DESTRUCTIVE migration - deleting all collections..." }

        // Step 1: Delete Weaviate vector collections (including legacy)
        logger.warn { "Deleting Weaviate collections..." }
        deleteCollectionIfExists(client, KNOWLEDGE_COLLECTION)
        deleteCollectionIfExists(client, "KnowledgeText") // Legacy
        deleteCollectionIfExists(client, "KnowledgeCode") // Legacy
        logger.info { "✓ Weaviate collections deleted" }

        // Step 2: Delete MongoDB indexing state collections
        logger.warn { "Deleting MongoDB indexing state collections..." }
        deleteMongoDbIndexingCollections()
        logger.info { "✓ MongoDB indexing collections deleted" }

        logger.info { "Migration preparation complete - new schema will be created" }
    }

    private suspend fun deleteMongoDbIndexingCollections() {
        INDEXING_MONGODB_COLLECTIONS.forEach { collectionName ->
            try {
                val exists = mongoTemplate.collectionExists(collectionName).block() ?: false
                if (exists) {
                    mongoTemplate.dropCollection(collectionName).block()
                    logger.info { "  ✓ Dropped MongoDB collection: $collectionName" }
                } else {
                    logger.debug { "  - Collection $collectionName does not exist (OK)" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "  ⚠ Failed to drop MongoDB collection $collectionName: ${e.message}" }
            }
        }
    }

    private fun deleteCollectionIfExists(
        client: WeaviateClient,
        className: String,
    ) {
        try {
            val result =
                client
                    .schema()
                    .classDeleter()
                    .withClassName(className)
                    .run()
            if (result.hasErrors()) {
                logger.warn { "Failed to delete $className: ${result.error.messages.joinToString()}" }
            } else {
                logger.info { "✓ Deleted collection: $className" }
            }
        } catch (e: Exception) {
            logger.debug { "Collection $className does not exist (OK)" }
        }
    }

    private suspend fun ensureSchemaExists(client: WeaviateClient) {
        val schema =
            client
                .schema()
                .getter()
                .run()
                ?.result
        val existingClasses = schema?.classes?.map { it.className }?.toSet() ?: emptySet()

        if (KNOWLEDGE_COLLECTION !in existingClasses) {
            createCollection(client, KNOWLEDGE_COLLECTION)
        } else {
            logger.info { "Collection $KNOWLEDGE_COLLECTION already exists" }
        }
    }

    private fun createCollection(
        client: WeaviateClient,
        className: String,
    ) {
        logger.info { "Creating collection: $className" }

        // Get embedding model configuration - use first available embedding model
        val embeddingModel = modelsProperties.models.values.flatten().firstOrNull()
        val vectorDimensions = embeddingModel?.dimension ?: 1536 // Default OpenAI dimension

        val weaviateClass =
            WeaviateClass
                .builder()
                .className(className)
                .description("Unified knowledge base - single embedding for all content")
                .properties(SCHEMA_PROPERTIES)
                .vectorIndexType("hnsw")
                .vectorizer("none") // We provide vectors externally
                .build()

        val result =
            client
                .schema()
                .classCreator()
                .withClass(weaviateClass)
                .run()

        if (result.hasErrors()) {
            val errors = result.error.messages.joinToString()
            throw IllegalStateException("Failed to create collection $className: $errors")
        }

        logger.info { "✓ Created collection: $className (dimensions: $vectorDimensions)" }
    }
}
