package com.jervis.service.scheduling

import com.jervis.entity.ScheduledTaskDocument
import com.jervis.repository.ScheduledTaskMongoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for basic task management operations (CRUD only).
 * Scheduling/dispatching handled by BackgroundEngine scheduler loop.
 */
@Service
class TaskManagementService(
    private val scheduledTaskRepository: ScheduledTaskMongoRepository,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Schedule a new task
     */
    suspend fun scheduleTask(
        clientId: ObjectId,
        projectId: ObjectId?,
        content: String,
        taskName: String,
        scheduledAt: Instant,
        cronExpression: String? = null,
        correlationId: String? = null,
    ): ScheduledTaskDocument =
        withContext(Dispatchers.IO) {
            val task =
                ScheduledTaskDocument(
                    clientId = clientId,
                    projectId = projectId,
                    content = content,
                    taskName = taskName,
                    scheduledAt = scheduledAt,
                    cronExpression = cronExpression,
                    correlationId = correlationId,
                )

            val savedTask = scheduledTaskRepository.save(task)
            logger.info { "Scheduled task: ${savedTask.taskName} for client: $clientId, project: ${projectId ?: "N/A"} at: $scheduledAt" }
            savedTask
        }

    /**
     * Update scheduled time for recurring task (cron)
     */
    suspend fun updateScheduledTime(
        taskId: ObjectId,
        newScheduledAt: Instant,
    ): ScheduledTaskDocument? =
        withContext(Dispatchers.IO) {
            val task = scheduledTaskRepository.findById(taskId) ?: return@withContext null
            val updated = task.copy(scheduledAt = newScheduledAt)
            scheduledTaskRepository.save(updated)
        }

    /**
     * Cancel/delete a task
     */
    suspend fun cancelTask(taskId: ObjectId): Boolean =
        withContext(Dispatchers.IO) {
            val exists = scheduledTaskRepository.existsById(taskId)
            if (exists) {
                scheduledTaskRepository.deleteById(taskId)
                logger.info { "Cancelled/deleted task: $taskId" }
                true
            } else {
                false
            }
        }
}
