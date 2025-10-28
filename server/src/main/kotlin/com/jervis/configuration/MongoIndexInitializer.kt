package com.jervis.configuration

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.IndexDefinition
import org.springframework.stereotype.Component

@Component
class MongoIndexInitializer(
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun initializeIndexes() {
        runBlocking {
            logger.info { "Initializing MongoDB indexes..." }

            cleanupOrphanedIndexes()
            // Note: Removed index creation for dead collections:
            // - background_tasks (no entity/repository)
            // - background_artifacts (no entity/repository)
            // - coverage_snapshots (no entity/repository)
            // - context_thread_links (removed with TaskContext)
            // - service_messages (removed with ServiceListener)

            logger.info { "MongoDB indexes initialized successfully" }
        }
    }

    private suspend fun cleanupOrphanedIndexes() {
        try {
            val clientsCollection = "clients"
            val indexOps = mongoTemplate.indexOps(clientsCollection)
            val existingIndexes = indexOps.indexInfo.collectList().awaitSingleOrNull() ?: emptyList()

            val slugIndex = existingIndexes.find { it.name == "slug" }
            if (slugIndex != null) {
                logger.info { "Dropping orphaned 'slug' index from $clientsCollection collection" }
                indexOps.dropIndex("slug").awaitSingleOrNull()
                logger.info { "Successfully dropped orphaned 'slug' index" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to cleanup orphaned indexes: ${e.message}" }
        }
    }

    private suspend fun createIndex(
        collection: String,
        index: IndexDefinition,
    ) {
        val indexOps = mongoTemplate.indexOps(collection)
        try {
            indexOps.ensureIndex(index).awaitSingleOrNull()
            logger.debug { "Ensured index for $collection" }
        } catch (e: Exception) {
            when {
                e.message?.contains("IndexOptionsConflict") == true ||
                    e.message?.contains("already exists with a different name") == true -> {
                    logger.info { "Index conflict detected for $collection, dropping all non-system indexes and recreating" }
                    try {
                        indexOps.dropAllIndexes().awaitSingleOrNull()
                        indexOps.ensureIndex(index).awaitSingleOrNull()
                        logger.info { "Successfully recreated index for $collection after dropping conflicting indexes" }
                    } catch (retryException: Exception) {
                        logger.warn(retryException) { "Failed to recreate index for $collection: ${retryException.message}" }
                    }
                }

                else -> {
                    logger.warn(e) { "Failed to create index for $collection: ${e.message}" }
                }
            }
        }
    }
}
