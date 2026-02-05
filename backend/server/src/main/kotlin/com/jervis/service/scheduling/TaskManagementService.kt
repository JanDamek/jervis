package com.jervis.service.scheduling

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.repository.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
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
    private val scheduledTaskRepository: TaskRepository,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Schedule a new task
     */
    suspend fun scheduleTask(
        clientId: ClientId,
        projectId: ProjectId?,
        content: String,
        taskName: String,
        scheduledAt: Instant,
        cronExpression: String? = null,
        correlationId: String? = null,
    ): TaskDocument =
        withContext(Dispatchers.IO) {
            val task =
                TaskDocument(
                    clientId = clientId,
                    projectId = projectId,
                    content = content,
                    taskName = taskName,
                    scheduledAt = scheduledAt,
                    cronExpression = cronExpression,
                    correlationId = correlationId ?: ObjectId().toString(),
                    sourceUrn = SourceUrn.scheduled(taskName),
                    type = TaskTypeEnum.SCHEDULED_TASK,
                )

            val savedTask = scheduledTaskRepository.save(task)
            logger.info { "Scheduled task: ${savedTask.taskName} for client: $clientId, project: ${projectId ?: "N/A"} at: $scheduledAt" }
            savedTask
        }

    /**
     * Update scheduled time for a recurring task (cron)
     */
    suspend fun updateScheduledTime(
        taskId: TaskId,
        newScheduledAt: Instant,
    ): TaskDocument? =
        withContext(Dispatchers.IO) {
            val task = scheduledTaskRepository.findAll().toList().find { it.id == taskId } ?: return@withContext null
            val updated = task.copy(scheduledAt = newScheduledAt)
            scheduledTaskRepository.save(updated)
        }

    /**
     * Cancel/delete a task
     */
    suspend fun cancelTask(taskId: TaskId): Boolean =
        withContext(Dispatchers.IO) {
            val task = scheduledTaskRepository.findAll().toList().find { it.id == taskId }
            if (task != null) {
                scheduledTaskRepository.delete(task)
                logger.info { "Cancelled/deleted task: $taskId" }
                true
            } else {
                false
            }
        }
}
