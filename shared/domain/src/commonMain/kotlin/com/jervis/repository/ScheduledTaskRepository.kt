package com.jervis.repository

import com.jervis.domain.task.ScheduledTaskStatusEnum
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
    private val agentOrchestratorService: IAgentOrchestratorService
) {

    /**
     * Schedule a new task
     */
    suspend fun scheduleTask(
        projectId: String,
        taskName: String,
        taskInstruction: String,
        cronExpression: String?,
        priority: Int
    ): ScheduledTaskDto {
        return taskSchedulingService.scheduleTask(
            projectId = projectId,
            taskName = taskName,
            taskInstruction = taskInstruction,
            cronExpression = cronExpression,
            priority = priority
        )
    }

    /**
     * Get task by ID
     */
    suspend fun findById(taskId: String): ScheduledTaskDto? {
        return taskSchedulingService.findById(taskId)
    }

    /**
     * List all tasks
     */
    suspend fun listAllTasks(): List<ScheduledTaskDto> {
        return taskSchedulingService.listAllTasks()
    }

    /**
     * List tasks for a specific project
     */
    suspend fun listTasksForProject(projectId: String): List<ScheduledTaskDto> {
        return taskSchedulingService.listTasksForProject(projectId)
    }

    /**
     * List pending tasks
     */
    suspend fun listPendingTasks(): List<ScheduledTaskDto> {
        return taskSchedulingService.listPendingTasks()
    }

    /**
     * Cancel a task
     */
    suspend fun cancelTask(taskId: String) {
        taskSchedulingService.cancelTask(taskId)
    }

    /**
     * Retry a failed task
     */
    suspend fun retryTask(taskId: String): ScheduledTaskDto {
        return taskSchedulingService.retryTask(taskId)
    }

    /**
     * Update task status
     */
    suspend fun updateTaskStatus(
        taskId: String,
        status: String,
        errorMessage: String?
    ): ScheduledTaskDto {
        return taskSchedulingService.updateTaskStatus(taskId, status, errorMessage)
    }

    /**
     * Get tasks by status
     */
    fun getTasksByStatus(taskStatus: ScheduledTaskStatusEnum): List<ScheduledTaskDto> {
        return taskSchedulingService.getTasksByStatus(taskStatus)
    }

    /**
     * Execute a task immediately through the agent orchestrator
     */
    suspend fun executeTaskNow(
        taskInstruction: String,
        clientId: String,
        projectId: String,
        wsSessionId: String? = null
    ) {
        val chatRequestContextDto = ChatRequestContextDto(
            clientId = clientId,
            projectId = projectId,
            quick = false,
            existingContextId = null
        )

        agentOrchestratorService.handle(
            ChatRequestDto(
                text = taskInstruction,
                context = chatRequestContextDto,
                wsSessionId = wsSessionId
            )
        )
    }
}
