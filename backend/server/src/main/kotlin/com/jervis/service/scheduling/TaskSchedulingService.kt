package com.jervis.service.scheduling

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.TaskId
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.repository.TaskRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for managing scheduled tasks (CRUD operations only).
 * Task execution is handled by BackgroundEngine scheduler loop.
 */
@Service
class TaskSchedulingService(
    private val scheduledTaskRepository: TaskRepository,
    private val taskManagementService: TaskManagementService,
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
        taskManagementService.scheduleTask(
            clientId = clientId,
            projectId = projectId,
            content = content,
            taskName = taskName,
            scheduledAt = scheduledAt,
            cronExpression = cronExpression,
            correlationId = correlationId,
        )

    /**
     * Cancel a task
     */
    suspend fun cancelTask(taskId: TaskId): Boolean = taskManagementService.cancelTask(taskId)

    suspend fun findById(taskId: TaskId): TaskDocument? = scheduledTaskRepository.findAll().toList().find { it.id == taskId }

    suspend fun listAllTasks(): List<TaskDocument> = scheduledTaskRepository.findAll().toList()

    suspend fun listTasksForProject(projectId: ProjectId): List<TaskDocument> =
        scheduledTaskRepository.findByProjectIdAndType(projectId, TaskTypeEnum.SCHEDULED_TASK).toList()

    suspend fun listTasksForClient(clientId: ClientId): List<TaskDocument> =
        scheduledTaskRepository.findByClientIdAndType(clientId, TaskTypeEnum.SCHEDULED_TASK).toList()
}
