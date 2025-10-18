package com.jervis.service

import com.jervis.domain.task.ScheduledTaskStatus
import com.jervis.dto.ScheduledTaskDto

interface ITaskSchedulingService {
    suspend fun scheduleTask(
        projectId: String,
        taskName: String,
        taskInstruction: String,
        cronExpression: String?,
        priority: Int,
    ): ScheduledTaskDto

    suspend fun findById(taskId: String): ScheduledTaskDto?

    suspend fun listAllTasks(): List<ScheduledTaskDto>

    suspend fun listTasksForProject(projectId: String): List<ScheduledTaskDto>

    suspend fun listPendingTasks(): List<ScheduledTaskDto>

    suspend fun cancelTask(taskId: String)

    suspend fun retryTask(taskId: String): ScheduledTaskDto

    suspend fun updateTaskStatus(
        taskId: String,
        status: String,
        errorMessage: String?,
    ): ScheduledTaskDto

    fun getTasksByStatus(taskStatus: ScheduledTaskStatus): List<ScheduledTaskDto>
}
