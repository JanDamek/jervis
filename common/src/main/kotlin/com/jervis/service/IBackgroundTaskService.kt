package com.jervis.service

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

/**
 * HTTP Exchange interface for background task management.
 * Provides endpoints for creating and querying background cognitive tasks.
 */
@HttpExchange("/api/background")
interface IBackgroundTaskService {
    /**
     * List background tasks with optional status filter.
     *
     * @param status Optional status filter (PENDING, RUNNING, COMPLETED, etc.)
     * @return List of background tasks as DTOs
     */
    @GetExchange("/tasks")
    suspend fun listTasks(
        @RequestParam(required = false) status: String?,
    ): List<Map<String, Any>>

    /**
     * Get a specific background task by ID.
     *
     * @param taskId The task ID
     * @return Task DTO or null if not found
     */
    @GetExchange("/tasks/{taskId}")
    suspend fun getTask(
        @PathVariable taskId: String,
    ): Map<String, Any>?

    /**
     * Create a new background task.
     *
     * @param request Task creation request with type, target, priority, etc.
     * @return Created task DTO
     */
    @PostExchange("/tasks")
    suspend fun createTask(
        @RequestBody request: Map<String, Any>,
    ): Map<String, Any>

    /**
     * List artifacts produced by background tasks.
     *
     * @param taskId Optional task ID filter
     * @param type Optional artifact type filter
     * @return List of artifact DTOs
     */
    @GetExchange("/artifacts")
    suspend fun listArtifacts(
        @RequestParam(required = false) taskId: String?,
        @RequestParam(required = false) type: String?,
    ): List<Map<String, Any>>

    /**
     * Get latest coverage snapshot for a project.
     *
     * @param projectKey The project identifier
     * @return Coverage snapshot DTO or null
     */
    @GetExchange("/coverage/{projectKey}")
    suspend fun getCoverage(
        @PathVariable projectKey: String,
    ): Map<String, Any>?

    /**
     * List coverage snapshots for a project with limit.
     *
     * @param projectKey The project identifier
     * @param limit Maximum number of snapshots to return
     * @return List of coverage snapshot DTOs
     */
    @GetExchange("/coverage")
    suspend fun listCoverage(
        @RequestParam projectKey: String,
        @RequestParam(defaultValue = "10") limit: Int,
    ): List<Map<String, Any>>

    /**
     * Get background task processing statistics.
     *
     * @return Statistics DTO with task counts by status
     */
    @GetExchange("/stats")
    suspend fun getStats(): Map<String, Any>
}
