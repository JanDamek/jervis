package com.jervis.service.scheduling

import com.jervis.domain.task.ScheduledTaskStatus
import com.jervis.entity.mongo.ScheduledTaskDocument
import com.jervis.repository.mongo.ScheduledTaskMongoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for basic task management operations without dependencies on execution services.
 * This service provides task creation and cancellation capabilities without depending on
 * AgentOrchestrator, avoiding circular dependencies with McpTools.
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
        projectId: ObjectId,
        taskInstruction: String,
        taskName: String,
        scheduledAt: Instant,
        taskParameters: Map<String, String> = emptyMap(),
        priority: Int = 0,
        maxRetries: Int = 3,
        cronExpression: String? = null,
        createdBy: String = "system",
    ): ScheduledTaskDocument =
        withContext(Dispatchers.IO) {
            val task =
                ScheduledTaskDocument(
                    projectId = projectId,
                    taskInstruction = taskInstruction,
                    taskName = taskName,
                    scheduledAt = scheduledAt,
                    taskParameters = taskParameters,
                    priority = priority,
                    maxRetries = maxRetries,
                    cronExpression = cronExpression,
                    createdBy = createdBy,
                )

            val savedTask = scheduledTaskRepository.save(task)
            logger.info { "Scheduled task: ${savedTask.taskName} for project: $projectId at: $scheduledAt" }
            savedTask
        }

    /**
     * Cancel a task
     */
    suspend fun cancelTask(taskId: ObjectId): Boolean =
        withContext(Dispatchers.IO) {
            val task = scheduledTaskRepository.findById(taskId) ?: return@withContext false

            if (task.status == ScheduledTaskStatus.PENDING ||
                task.status == ScheduledTaskStatus.FAILED
            ) {
                val cancelledTask =
                    task.copy(
                        status = ScheduledTaskStatus.CANCELLED,
                        lastUpdatedAt = Instant.now(),
                    )
                scheduledTaskRepository.save(cancelledTask)
                logger.info { "Cancelled task: ${task.taskName}" }
                true
            } else {
                logger.warn { "Cannot cancel task in status: ${task.status}" }
                false
            }
        }

    /**
     * Update task status
     */
    suspend fun updateTaskStatus(
        task: ScheduledTaskDocument,
        newStatus: ScheduledTaskStatus,
        errorMessage: String? = null,
    ): ScheduledTaskDocument =
        withContext(Dispatchers.IO) {
            val updatedTask =
                task.copy(
                    status = newStatus,
                    errorMessage = errorMessage,
                    startedAt =
                        if (newStatus == ScheduledTaskStatus.RUNNING &&
                            task.startedAt == null
                        ) {
                            Instant.now()
                        } else {
                            task.startedAt
                        },
                    completedAt =
                        if (newStatus == ScheduledTaskStatus.COMPLETED ||
                            newStatus == ScheduledTaskStatus.FAILED
                        ) {
                            Instant.now()
                        } else {
                            task.completedAt
                        },
                    lastUpdatedAt = Instant.now(),
                )
            scheduledTaskRepository.save(updatedTask)
        }
}
