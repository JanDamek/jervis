package com.jervis.service

import com.jervis.dto.ScheduledTaskDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface ITaskSchedulingService {
    suspend fun scheduleTask(
        clientId: String,
        projectId: String?,
        taskName: String,
        content: String,
        cronExpression: String?,
        correlationId: String?,
    ): ScheduledTaskDto

    suspend fun findById(taskId: String): ScheduledTaskDto?

    suspend fun listAllTasks(): List<ScheduledTaskDto>

    suspend fun listTasksForProject(projectId: String): List<ScheduledTaskDto>

    suspend fun listTasksForClient(clientId: String): List<ScheduledTaskDto>

    suspend fun cancelTask(taskId: String)
}
