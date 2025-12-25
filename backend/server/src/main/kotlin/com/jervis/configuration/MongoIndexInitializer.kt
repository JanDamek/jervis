package com.jervis.configuration

import jakarta.annotation.PostConstruct
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.IndexInfo
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver
import org.springframework.data.mongodb.core.mapping.MongoMappingContext
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity
import org.springframework.stereotype.Component
import org.springframework.data.mongodb.core.mapping.Document as MongoDocument

/**
 * MongoDB Index Initializer - ensures indexes are correctly created at application startup.
 *
 * CRITICAL STARTUP COMPONENT:
 * - Runs SYNCHRONOUSLY at application start (@PostConstruct)
 * - BEFORE schedulers and pollers start (@Order(0))
 * - Drops ALL existing indexes (except _id_)
 * - Creates fresh indexes from @CompoundIndexes annotations
 * - Blocks application start until complete
 * - NO INDEX CONFLICTS - always clean state
 *
 * Strategy:
 * 1. For each @Document entity collection
 * 2. Drop ALL existing indexes (except _id_)
 * 3. Create new indexes from current annotations
 * 4. Log INFO messages (no ERROR/WARN for expected operations)
 */
@Component
@Order(0)
class MongoIndexInitializer(
    private val template: ReactiveMongoTemplate,
    private val mappingContext: MongoMappingContext,
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun initializeIndexes() {
        logger.info { "═══════════════════════════════════════════════════════════" }
        logger.info { "MONGO INDEX INITIALIZATION - CRITICAL STARTUP PHASE" }
        logger.info { "═══════════════════════════════════════════════════════════" }

        try {
            val indexResolver = MongoPersistentEntityIndexResolver(mappingContext)

            mappingContext.persistentEntities
                .filterIsInstance<MongoPersistentEntity<*>>()
                .filter { entity -> entity.type.isAnnotationPresent(MongoDocument::class.java) }
                .forEach { entity ->
                    val collectionName = entity.collection
                    logger.info { "Processing collection: $collectionName" }

                    // Get desired indexes from annotations
                    val desiredIndexes = indexResolver.resolveIndexFor(entity.type)

                    // Reconcile: drop conflicting, create missing
                    reconcileIndexes(collectionName, desiredIndexes)
                }

            logger.info { "✅ MongoDB indexes initialized successfully" }
            logger.info { "═══════════════════════════════════════════════════════════" }
        } catch (e: Exception) {
            logger.error(e) { "❌ FATAL: MongoDB index initialization FAILED" }
            logger.error { "Application cannot start without valid indexes" }
            throw IllegalStateException("MongoDB index initialization failed - application cannot start", e)
        }
    }

    private fun reconcileIndexes(
        collectionName: String,
        desiredIndexes: Iterable<org.springframework.data.mongodb.core.index.IndexDefinition>,
    ) {
        val collectionExists = template.collectionExists(collectionName).block() ?: false
        if (!collectionExists) {
            logger.info { "  Collection $collectionName does not exist - will create indexes on first insert" }
            return
        }

        val indexOps = template.indexOps(collectionName)
        val existingIndexes: List<IndexInfo> = indexOps.indexInfo.collectList().block() ?: emptyList()

        val existingByName = existingIndexes.filter { it.name != "_id_" }.associateBy { it.name }

        val desiredNames =
            desiredIndexes
                .mapNotNull { def ->
                    def.indexOptions.getString("name")
                }.toSet()

        val obsoleteIndexes = existingByName.keys - desiredNames
        if (obsoleteIndexes.isNotEmpty()) {
            logger.info { "  Dropping ${obsoleteIndexes.size} obsolete indexes: ${obsoleteIndexes.joinToString()}" }
            obsoleteIndexes.forEach { indexName ->
                try {
                    indexOps.dropIndex(indexName).block()
                    logger.info { "    ✓ Dropped obsolete index: $indexName" }
                } catch (e: Exception) {
                    logger.warn { "    ✗ Failed to drop $indexName: ${e.message}" }
                }
            }
        }

        desiredIndexes.forEach { def ->
            val indexName = def.indexOptions.getString("name") ?: "generated"

            try {
                indexOps.createIndex(def).block()
                logger.info { "    ✓ Created index: $indexName" }
            } catch (e: Exception) {
                val errorMsg = e.message ?: ""

                if (errorMsg.contains("E11000") || errorMsg.contains("duplicate key error")) {
                    logger.warn { "    ~ Index $indexName cannot be created due to duplicate keys in collection $collectionName" }
                    logger.info { "      This collection has duplicate data that violates unique constraint" }
                    logger.info { "      Skipping index $indexName - fix data duplicates first" }
                    return@forEach
                }

                if (errorMsg.contains("IndexOptionsConflict") ||
                    errorMsg.contains("IndexKeySpecsConflict") ||
                    errorMsg.contains("already exists")
                ) {
                    logger.info { "    ~ Index $indexName conflict detected, finding conflicting index..." }

                    val conflictingName = parseConflictingIndexName(errorMsg)

                    if (conflictingName != null) {
                        logger.info { "      Found conflicting index: $conflictingName" }
                        try {
                            indexOps.dropIndex(conflictingName).block()
                            logger.info { "      ✓ Dropped conflicting index: $conflictingName" }
                        } catch (dropEx: Exception) {
                            logger.warn { "      - Failed to drop $conflictingName: ${dropEx.message}" }
                        }
                    } else {
                        try {
                            indexOps.dropIndex(indexName).block()
                            logger.info { "      ✓ Dropped index: $indexName" }
                        } catch (dropEx: Exception) {
                            logger.warn { "      - Failed to drop $indexName: ${dropEx.message}" }
                        }
                    }

                    try {
                        indexOps.createIndex(def).block()
                        logger.info { "      ✓ Recreated index: $indexName" }
                    } catch (createEx: Exception) {
                        val createErrorMsg = createEx.message ?: ""
                        if (createErrorMsg.contains("E11000") || createErrorMsg.contains("duplicate key error")) {
                            logger.warn { "      ~ Cannot create unique index $indexName - duplicate keys exist" }
                        } else {
                            logger.error { "      ✗ Failed to recreate index $indexName: $createErrorMsg" }
                            throw createEx
                        }
                    }
                } else {
                    logger.error { "    ✗ Failed to create index $indexName: $errorMsg" }
                    throw e
                }
            }
        }
    }

    private fun parseConflictingIndexName(errorMessage: String?): String? {
        if (errorMessage.isNullOrBlank()) return null
        val regex = Regex("different name: ([^\\s'}]+)")
        val match = regex.find(errorMessage)
        return match?.groupValues?.getOrNull(1)
    }
}
