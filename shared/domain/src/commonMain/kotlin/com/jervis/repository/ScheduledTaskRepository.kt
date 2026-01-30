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
) : BaseRepository() {
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
    ): ScheduledTaskDto = safeRpcCall("scheduleTask") {
        taskSchedulingService.scheduleTask(
            clientId = clientId,
            projectId = projectId,
            taskName = taskName,
            content = content,
            cronExpression = cronExpression,
            correlationId = correlationId,
        )
    }

    /**
     * Get task by ID
     */
    suspend fun findById(taskId: String): ScheduledTaskDto? = safeRpcCall("findScheduledTaskById") {
        taskSchedulingService.findById(taskId)
    }

    /**
     * List all tasks
     */
    suspend fun listAllTasks(): List<ScheduledTaskDto> = safeRpcListCall("listAllScheduledTasks") {
        taskSchedulingService.listAllTasks()
    }

    /**
     * List tasks for a specific project
     */
    suspend fun listTasksForProject(projectId: String): List<ScheduledTaskDto> = safeRpcListCall("listScheduledTasksForProject") {
        taskSchedulingService.listTasksForProject(projectId)
    }

    /**
     * List tasks for a specific client
     */
    suspend fun listTasksForClient(clientId: String): List<ScheduledTaskDto> = safeRpcListCall("listScheduledTasksForClient") {
        taskSchedulingService.listTasksForClient(clientId)
    }

    /**
     * Cancel a task
     */
    suspend fun cancelTask(taskId: String) {
        safeRpcCall("cancelScheduledTask") {
            taskSchedulingService.cancelTask(taskId)
        }
    }

    /**
     * Execute a task immediately through the agent orchestrator.
     * Sends the message - responses will arrive via subscribeToChat stream.
     */
    suspend fun executeTaskNow(
        content: String,
        clientId: String,
        projectId: String?,
    ) {
        val chatRequestContextDto =
            ChatRequestContextDto(
                clientId = clientId,
                projectId = projectId,
                quick = false,
            )

        safeRpcCall("executeScheduledTaskNow") {
            agentOrchestratorService.sendMessage(
                ChatRequestDto(
                    text = content,
                    context = chatRequestContextDto,
                ),
            )
        }
    }
}
