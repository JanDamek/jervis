package com.jervis.rag._internal

import com.jervis.configuration.properties.ModelsProperties
import com.jervis.configuration.properties.WeaviateProperties
import com.jervis.domain.model.ModelTypeEnum
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
 * Migration strategy:
 * 1. Check if schema matches current configuration
 * 2. If NOT match: DELETE all collections (KnowledgeText, KnowledgeCode)
 * 3. CREATE new collections with correct schema
 * 4. Schema fields: content, knowledgeType, knowledgeSeverity, documentId, documentTitle,
 *    documentLocation, chunkIndex, totalChunks, clientId, projectId, relatedDocuments
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
        private const val TEXT_COLLECTION = "KnowledgeText"
        private const val CODE_COLLECTION = "KnowledgeCode"

        // MongoDB collections related to indexing and RAG - will be deleted during migration
        private val INDEXING_MONGODB_COLLECTIONS = listOf(
            "email_messages",
            "jira_issue_index",
            "confluence_pages",
            "git_commits",
            "indexed_links",
            "vector_store_index",
        )

        private val SCHEMA_PROPERTIES = listOf(
            Property.builder().name("content").dataType(listOf(DataType.TEXT)).build(),
            Property.builder().name("knowledgeType").dataType(listOf(DataType.TEXT)).build(),
            Property.builder().name("knowledgeSeverity").dataType(listOf(DataType.TEXT)).build(),
            Property.builder().name("documentId").dataType(listOf(DataType.TEXT)).build(),
            Property.builder().name("documentTitle").dataType(listOf(DataType.TEXT)).build(),
            Property.builder().name("documentLocation").dataType(listOf(DataType.TEXT)).build(),
            Property.builder().name("chunkIndex").dataType(listOf(DataType.INT)).build(),
            Property.builder().name("totalChunks").dataType(listOf(DataType.INT)).build(),
            Property.builder().name("clientId").dataType(listOf(DataType.TEXT)).build(),
            Property.builder().name("projectId").dataType(listOf(DataType.TEXT)).build(),
            Property.builder().name("relatedDocuments").dataType(listOf(DataType.TEXT_ARRAY)).build(),
        )

        private val EXPECTED_FIELD_NAMES = SCHEMA_PROPERTIES.map { it.name }.toSet()
    }

    @PostConstruct
    fun initializeSchema() {
        if (!weaviateProperties.enabled) {
            logger.info { "Weaviate is DISABLED - skipping schema initialization" }
            return
        }

        logger.info { "═══════════════════════════════════════════════════════════" }
        logger.info { "WEAVIATE SCHEMA INITIALIZATION - CRITICAL STARTUP PHASE" }
        logger.info { "═══════════════════════════════════════════════════════════" }

        try {
            val client = createClient()

            // Check migration synchronously using runBlocking
            val migrationNeeded = runBlocking {
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

            logger.info { "✅ Weaviate schema ready: $TEXT_COLLECTION, $CODE_COLLECTION" }
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
            val schema = client.schema().getter().run()?.result ?: return false
            val classes = schema.classes ?: return false

            val textClass = classes.find { it.className == TEXT_COLLECTION }
            val codeClass = classes.find { it.className == CODE_COLLECTION }

            if (textClass == null && codeClass == null) {
                logger.info { "No existing collections - will create fresh schema" }
                return false
            }

            // Check if either collection needs migration
            val textNeedsMigration = textClass?.let { needsMigration(it) } ?: false
            val codeNeedsMigration = codeClass?.let { needsMigration(it) } ?: false

            return textNeedsMigration || codeNeedsMigration
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

        return false
    }

    private suspend fun performMigration(client: WeaviateClient) {
        logger.warn { "Starting DESTRUCTIVE migration - deleting all collections..." }

        // Step 1: Delete Weaviate vector collections
        logger.warn { "Deleting Weaviate collections..." }
        deleteCollectionIfExists(client, TEXT_COLLECTION)
        deleteCollectionIfExists(client, CODE_COLLECTION)
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

    private fun deleteCollectionIfExists(client: WeaviateClient, className: String) {
        try {
            val result = client.schema().classDeleter().withClassName(className).run()
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
        val schema = client.schema().getter().run()?.result
        val existingClasses = schema?.classes?.map { it.className }?.toSet() ?: emptySet()

        if (TEXT_COLLECTION !in existingClasses) {
            createCollection(client, TEXT_COLLECTION, ModelTypeEnum.EMBEDDING_TEXT)
        } else {
            logger.info { "Collection $TEXT_COLLECTION already exists" }
        }

        if (CODE_COLLECTION !in existingClasses) {
            createCollection(client, CODE_COLLECTION, ModelTypeEnum.EMBEDDING_CODE)
        } else {
            logger.info { "Collection $CODE_COLLECTION already exists" }
        }
    }

    private fun createCollection(client: WeaviateClient, className: String, modelType: ModelTypeEnum) {
        logger.info { "Creating collection: $className" }

        // Get embedding model configuration
        val embeddingModels = modelsProperties.models[modelType] ?: emptyList()
        val vectorDimensions = embeddingModels.firstOrNull()?.dimension ?: 1536 // Default OpenAI dimension

        val weaviateClass = WeaviateClass
            .builder()
            .className(className)
            .description("Knowledge base for ${modelType.name}")
            .properties(SCHEMA_PROPERTIES)
            .vectorIndexType("hnsw")
            .vectorizer("none") // We provide vectors externally
            .build()


        val result = client.schema().classCreator().withClass(weaviateClass).run()

        if (result.hasErrors()) {
            val errors = result.error.messages.joinToString()
            throw IllegalStateException("Failed to create collection $className: $errors")
        }

        logger.info { "✓ Created collection: $className (dimensions: $vectorDimensions)" }
    }
}
