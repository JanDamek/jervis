package com.jervis.repository

import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ScheduledTaskDto
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.ITaskSchedulingService

/**
 * Repository for Scheduled Task operations
 * Wraps ITaskSchedulingService and IAgentOrchestratorService with additional logic
 */
class ScheduledTaskRepository(
    private val taskSchedulingService: ITaskSchedulingService,
    private val agentOrchestratorService: IAgentOrchestratorService,
) {
    /**
     * Schedule a new task
     */
    suspend fun scheduleTask(
        clientId: String,
        projectId: String?,
        taskName: String,
        content: String,
        cronExpression: String?,
        correlationId: String?,
    ): ScheduledTaskDto =
        taskSchedulingService.scheduleTask(
            clientId = clientId,
            projectId = projectId,
            taskName = taskName,
            content = content,
            cronExpression = cronExpression,
            correlationId = correlationId,
        )

    /**
     * Get task by ID
     */
    suspend fun findById(taskId: String): ScheduledTaskDto? = taskSchedulingService.findById(taskId)

    /**
     * List all tasks
     */
    suspend fun listAllTasks(): List<ScheduledTaskDto> = taskSchedulingService.listAllTasks()

    /**
     * List tasks for a specific project
     */
    suspend fun listTasksForProject(projectId: String): List<ScheduledTaskDto> = taskSchedulingService.listTasksForProject(projectId)

    /**
     * List tasks for a specific client
     */
    suspend fun listTasksForClient(clientId: String): List<ScheduledTaskDto> = taskSchedulingService.listTasksForClient(clientId)

    /**
     * Cancel a task
     */
    suspend fun cancelTask(taskId: String) {
        taskSchedulingService.cancelTask(taskId)
    }

    /**
     * Execute a task immediately through the agent orchestrator
     */
    suspend fun executeTaskNow(
        content: String,
        clientId: String,
        projectId: String,
        wsSessionId: String? = null,
    ) {
        val chatRequestContextDto =
            ChatRequestContextDto(
                clientId = clientId,
                projectId = projectId,
                quick = false,
            )

        agentOrchestratorService.handle(
            ChatRequestDto(
                text = content,
                context = chatRequestContextDto,
            ),
        )
    }
}
