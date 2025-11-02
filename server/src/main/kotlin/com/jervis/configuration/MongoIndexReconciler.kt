package com.jervis.configuration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.IndexDefinition
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver
import org.springframework.data.mongodb.core.index.ReactiveIndexOperations
import org.springframework.data.mongodb.core.mapping.MongoMappingContext
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity
import org.springframework.stereotype.Component
import org.springframework.data.mongodb.core.mapping.Document as MongoDocument

/**
 * General rule for MongoDB indexes:
 * If an index already exists with the same keys but a different name or options,
 * drop the existing index and create the new one declared by annotations.
 *
 * This avoids IndexOptionsConflict (code 85) like:
 * "Index already exists with a different name: project_state_commitDate_idx".
 */
@Component
class MongoIndexReconciler(
    private val template: ReactiveMongoTemplate,
    private val mappingContext: MongoMappingContext,
) : ApplicationRunner {
    private val logger = KotlinLogging.logger {}

    override fun run(args: ApplicationArguments) {
        // Run asynchronously; do not block application startup
        CoroutineScope(Dispatchers.Default).launch {
            try {
                reconcileAllEntities()
            } catch (t: Throwable) {
                logger.error(t) { "Mongo index reconciliation failed" }
            }
        }
    }

    private suspend fun reconcileAllEntities() {
        val indexResolver = MongoPersistentEntityIndexResolver(mappingContext)
        mappingContext.persistentEntities
            .filterIsInstance<MongoPersistentEntity<*>>()
            .filter { entity -> entity.type.isAnnotationPresent(MongoDocument::class.java) }
            .forEach { entity ->
                val definitions = indexResolver.resolveIndexFor(entity.type)
                val indexOps = template.indexOps(entity.collection)
                definitions.forEach { def ->
                    ensureIndexWithConflictHandling(indexOps, def)
                }
            }
    }

    private suspend fun ensureIndexWithConflictHandling(
        indexOps: ReactiveIndexOperations,
        def: IndexDefinition,
    ) {
        try {
            indexOps.ensureIndex(def).awaitSingle()
        } catch (t: Throwable) {
            if (isIndexOptionsConflict(t)) {
                val conflictingName = parseConflictingIndexName(t.message)
                val desiredName = def.indexOptions?.getString("name") ?: "<generated>"
                if (conflictingName != null) {
                    logger.warn { "Dropping conflicting index '$conflictingName' and recreating '$desiredName'" }
                    try {
                        indexOps.dropIndex(conflictingName).awaitSingleOrNull()
                    } catch (dropError: Throwable) {
                        logger.warn(dropError) { "Failed to drop conflicting index '$conflictingName' (may have been removed already)" }
                    }
                    // retry creation
                    indexOps.ensureIndex(def).awaitSingle()
                } else {
                    // No index name parsed; rethrow to avoid unsafe mass-drop
                    throw t
                }
            } else {
                throw t
            }
        }
    }

    private fun isIndexOptionsConflict(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val msg = cur.message ?: ""
            if (msg.contains("IndexOptionsConflict") ||
                msg.contains("Index already exists with a different name") ||
                (msg.contains("already exists") && msg.contains("different options"))
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun parseConflictingIndexName(message: String?): String? {
        if (message.isNullOrBlank()) return null
        // Example message: "Index already exists with a different name: project_state_commitDate_idx"
        val regex = Regex("different name: ([^\\s}]+)")
        val match = regex.find(message)
        return match?.groupValues?.getOrNull(1)
    }
}
