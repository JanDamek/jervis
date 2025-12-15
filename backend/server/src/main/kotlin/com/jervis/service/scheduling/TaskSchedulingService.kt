package com.jervis.service.scheduling

import com.jervis.entity.ScheduledTaskDocument
import com.jervis.repository.ScheduledTaskMongoRepository
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for managing scheduled tasks (CRUD operations only).
 * Task execution is handled by BackgroundEngine scheduler loop.
 */
@Service
class TaskSchedulingService(
    private val scheduledTaskRepository: ScheduledTaskMongoRepository,
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
    ): ScheduledTaskDocument =
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
    suspend fun cancelTask(taskId: ObjectId): Boolean = taskManagementService.cancelTask(taskId)

    suspend fun findById(taskId: ObjectId): ScheduledTaskDocument? = scheduledTaskRepository.findById(taskId)

    suspend fun listAllTasks(): List<ScheduledTaskDocument> = scheduledTaskRepository.findAll().toList()

    suspend fun listTasksForProject(projectId: ObjectId): List<ScheduledTaskDocument> =
        scheduledTaskRepository.findByProjectId(projectId).toList()

    suspend fun listTasksForClient(clientId: ObjectId): List<ScheduledTaskDocument> =
        scheduledTaskRepository.findByClientId(clientId).toList()
}
