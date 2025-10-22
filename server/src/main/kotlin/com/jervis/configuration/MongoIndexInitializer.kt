package com.jervis.configuration

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.Index
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
            createBackgroundTasksIndexes()
            createBackgroundArtifactsIndexes()
            createCoverageSnapshotsIndexes()

            logger.info { "MongoDB indexes initialized successfully" }
        }
    }

    private suspend fun createBackgroundTasksIndexes() {
        val collection = "background_tasks"

        createIndex(
            collection,
            Index()
                .on("status", Sort.Direction.ASC)
                .on("priority", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC)
                .named("status_priority_created")
                .background(),
        )

        createIndex(
            collection,
            Index()
                .on("targetRef.type", Sort.Direction.ASC)
                .on("status", Sort.Direction.ASC)
                .named("target_type_status")
                .background(),
        )

        createIndex(
            collection,
            Index()
                .on("status", Sort.Direction.ASC)
                .named("status_idx")
                .background(),
        )

        createIndex(
            collection,
            Index()
                .on("taskType", Sort.Direction.ASC)
                .named("task_type_idx")
                .background(),
        )

        createIndex(
            collection,
            Index()
                .on("updatedAt", Sort.Direction.ASC)
                .named("updated_at_idx")
                .background(),
        )

        createIndex(
            collection,
            Index()
                .on("labels", Sort.Direction.ASC)
                .named("labels_idx")
                .background(),
        )

        logger.debug { "Created indexes for $collection" }
    }

    private suspend fun createBackgroundArtifactsIndexes() {
        val collection = "background_artifacts"

        createIndex(
            collection,
            Index()
                .on("taskId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC)
                .named("task_created")
                .background(),
        )

        createIndex(
            collection,
            Index()
                .on("type", Sort.Direction.ASC)
                .on("confidence", Sort.Direction.DESC)
                .named("type_confidence")
                .background(),
        )

        createIndex(
            collection,
            Index()
                .on("contentHash", Sort.Direction.ASC)
                .named("content_hash_unique")
                .unique()
                .background(),
        )

        createIndex(
            collection,
            Index()
                .on("type", Sort.Direction.ASC)
                .named("type_idx")
                .background(),
        )

        createIndex(
            collection,
            Index()
                .on("createdAt", Sort.Direction.DESC)
                .named("created_at_desc_idx")
                .background(),
        )

        logger.debug { "Created indexes for $collection" }
    }

    private suspend fun createCoverageSnapshotsIndexes() {
        val collection = "coverage_snapshots"

        createIndex(
            collection,
            Index()
                .on("projectKey", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC)
                .named("project_created")
                .background(),
        )

        createIndex(
            collection,
            Index()
                .on("projectKey", Sort.Direction.ASC)
                .named("project_key_idx")
                .background(),
        )

        createIndex(
            collection,
            Index()
                .on("createdAt", Sort.Direction.DESC)
                .named("created_at_desc_idx")
                .background(),
        )

        logger.debug { "Created indexes for $collection" }
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
