package com.jervis.service

import com.jervis.domain.task.ScheduledTaskStatusEnum
import com.jervis.dto.ScheduledTaskDto
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.DeleteExchange
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import org.springframework.web.service.annotation.PutExchange

@HttpExchange("/api/task-scheduling")
interface ITaskSchedulingService {
    @PostExchange
    suspend fun scheduleTask(
        @RequestParam projectId: String,
        @RequestParam taskName: String,
        @RequestParam taskInstruction: String,
        @RequestParam(required = false) cronExpression: String?,
        @RequestParam priority: Int,
    ): ScheduledTaskDto

    @GetExchange("/{taskId}")
    suspend fun findById(
        @PathVariable taskId: String,
    ): ScheduledTaskDto?

    @GetExchange("/list")
    suspend fun listAllTasks(): List<ScheduledTaskDto>

    @GetExchange("/project/{projectId}")
    suspend fun listTasksForProject(
        @PathVariable projectId: String,
    ): List<ScheduledTaskDto>

    @GetExchange("/pending")
    suspend fun listPendingTasks(): List<ScheduledTaskDto>

    @DeleteExchange("/{taskId}")
    suspend fun cancelTask(
        @PathVariable taskId: String,
    )

    @PostExchange("/{taskId}/retry")
    suspend fun retryTask(
        @PathVariable taskId: String,
    ): ScheduledTaskDto

    @PutExchange("/{taskId}/status")
    suspend fun updateTaskStatus(
        @PathVariable taskId: String,
        @RequestParam status: String,
        @RequestParam(required = false) errorMessage: String?,
    ): ScheduledTaskDto

    @GetExchange("/by-status")
    fun getTasksByStatus(
        @RequestParam taskStatus: ScheduledTaskStatusEnum,
    ): List<ScheduledTaskDto>
}
