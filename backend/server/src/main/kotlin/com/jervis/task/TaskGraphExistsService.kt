package com.jervis.task

import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Checks existence of task graphs in the shared `task_graphs` collection
 * (written by Python orchestrator, read here for UI metadata).
 *
 * Uses batch `$in` query — single DB roundtrip for N task IDs.
 */
@Service
class TaskGraphExistsService(
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    companion object {
        private const val COLLECTION = "task_graphs"
    }

    /**
     * Returns the set of taskIds that have a graph in the `task_graphs` collection.
     * Single query with `$in` operator — O(1) DB roundtrip regardless of N.
     */
    suspend fun findExistingGraphTaskIds(taskIds: Collection<String>): Set<String> {
        if (taskIds.isEmpty()) return emptySet()

        val query = Query(Criteria.where("task_id").`in`(taskIds))
        query.fields().include("task_id")

        return try {
            mongoTemplate.find(query, org.bson.Document::class.java, COLLECTION)
                .map { it.getString("task_id") }
                .collectList()
                .awaitSingle()
                .toSet()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to check graph existence for ${taskIds.size} tasks" }
            emptySet()
        }
    }
}
