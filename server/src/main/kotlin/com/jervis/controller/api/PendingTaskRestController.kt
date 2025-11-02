package com.jervis.controller.api

import com.jervis.service.background.PendingTaskService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

/**
 * REST API for PendingTask context management.
 * Allows manual updates to task context (e.g., adding dynamic goals).
 */
@RestController
@RequestMapping("/api/pending-tasks")
class PendingTaskRestController(
    private val pendingTaskService: PendingTaskService,
) {
    /**
     * Merge additional context into existing task.
     *
     * Example request body:
     * ```json
     * {
     *   "dynamicGoal": "Focus on concurrency implications of cache layer"
     * }
     * ```
     *
     * @param taskId Task ID (MongoDB ObjectId)
     * @param contextPatch Map of context keys to merge
     */
    @PatchMapping("/{taskId}/context")
    suspend fun updateTaskContext(
        @PathVariable taskId: String,
        @RequestBody contextPatch: Map<String, String>,
    ) {
        logger.info { "REST: Updating context for task $taskId with keys ${contextPatch.keys.joinToString(", ")}" }

        try {
            pendingTaskService.mergeContext(ObjectId(taskId), contextPatch)
            logger.info { "REST: Successfully updated context for task $taskId" }
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "REST: Failed to update context for task $taskId: ${e.message}" }
            throw e
        }
    }
}
