package com.jervis.service.scheduling

import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.entity.mongo.ScheduledTaskStatus
import com.jervis.repository.mongo.ScheduledTaskMongoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Service for querying scheduled tasks without dependencies on execution services.
 * This service provides read-only access to task data and is used by tools that
 * need to browse/view tasks without causing circular dependencies.
 */
@Service
class TaskQueryService(
    private val scheduledTaskRepository: ScheduledTaskMongoRepository,
) {
    /**
     * Get tasks for a project
     */
    suspend fun getTasksForProject(projectId: ObjectId): List<ScheduledTaskDocument> =
        withContext(Dispatchers.IO) {
            scheduledTaskRepository.findByProjectId(projectId).toList()
        }

    /**
     * Get tasks by status
     */
    suspend fun getTasksByStatus(status: ScheduledTaskStatus): List<ScheduledTaskDocument> =
        withContext(Dispatchers.IO) {
            scheduledTaskRepository.findByStatus(status).toList()
        }

    /**
     * Get task statistics
     */
    suspend fun getTaskStatistics(): Map<String, Long> =
        withContext(Dispatchers.IO) {
            mapOf(
                "pending" to scheduledTaskRepository.countByStatus(ScheduledTaskStatus.PENDING),
                "running" to scheduledTaskRepository.countByStatus(ScheduledTaskStatus.RUNNING),
                "completed" to scheduledTaskRepository.countByStatus(ScheduledTaskStatus.COMPLETED),
                "failed" to scheduledTaskRepository.countByStatus(ScheduledTaskStatus.FAILED),
                "cancelled" to scheduledTaskRepository.countByStatus(ScheduledTaskStatus.CANCELLED),
            )
        }

    /**
     * Get a specific task by ID
     */
    suspend fun getTaskById(taskId: ObjectId): ScheduledTaskDocument? =
        withContext(Dispatchers.IO) {
            scheduledTaskRepository.findById(taskId)
        }

    /**
     * Check if a task exists
     */
    suspend fun taskExists(taskId: ObjectId): Boolean =
        withContext(Dispatchers.IO) {
            scheduledTaskRepository.existsById(taskId)
        }
}
