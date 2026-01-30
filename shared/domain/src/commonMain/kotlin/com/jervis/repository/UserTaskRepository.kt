package com.jervis.repository

import com.jervis.dto.user.TaskRoutingMode
import com.jervis.dto.user.UserTaskCountDto
import com.jervis.dto.user.UserTaskDto
import com.jervis.service.IUserTaskService

/**
 * Repository for User Task operations
 * Wraps IUserTaskService with additional logic (caching, error handling, etc.)
 */
class UserTaskRepository(
    private val userTaskService: IUserTaskService
) : BaseRepository() {

    /**
     * List active user tasks for a client
     */
    suspend fun listActive(clientId: String): List<UserTaskDto> = safeRpcListCall("listActive") {
        userTaskService.listActive(clientId)
    }

    /**
     * Get active task count for a client
     */
    suspend fun activeCount(clientId: String): UserTaskCountDto = safeRpcCall("activeCount") {
        userTaskService.activeCount(clientId)
    }

    /**
     * Cancel a user task
     */
    suspend fun cancel(taskId: String): UserTaskDto = safeRpcCall("cancel") {
        userTaskService.cancel(taskId)
    }

    /**
     * Send user task to agent orchestrator with optional additional input
     * @param taskId Task ID to send
     * @param routingMode How to route the task (directly to agent or back to pending)
     * @param additionalInput Optional user comment/instructions
     */
    suspend fun sendToAgent(
        taskId: String,
        routingMode: TaskRoutingMode,
        additionalInput: String? = null
    ): UserTaskDto = safeRpcCall("sendToAgent") {
        userTaskService.sendToAgent(taskId, routingMode, additionalInput)
    }
}
