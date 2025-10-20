package com.jervis.controller.api

import com.jervis.domain.task.ScheduledTaskStatusEnum
import com.jervis.dto.ScheduledTaskDto
import com.jervis.mapper.toDto
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.scheduling.TaskSchedulingService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class TaskSchedulingRestController(
    private val taskSchedulingService: TaskSchedulingService,
) : ITaskSchedulingService {
    override suspend fun scheduleTask(
        @RequestParam projectId: String,
        @RequestParam taskName: String,
        @RequestParam taskInstruction: String,
        @RequestParam(required = false) cronExpression: String?,
        @RequestParam priority: Int,
    ): ScheduledTaskDto =
        taskSchedulingService
            .scheduleTask(
                projectId = ObjectId(projectId),
                taskInstruction = taskInstruction,
                taskName = taskName,
                scheduledAt = Instant.now(),
                taskParameters = emptyMap(),
                priority = priority,
                maxRetries = 3,
                cronExpression = cronExpression,
                createdBy = "system",
            ).toDto()

    override suspend fun findById(
        @PathVariable taskId: String,
    ): ScheduledTaskDto? = taskSchedulingService.findById(ObjectId(taskId))?.toDto()

    override suspend fun listAllTasks(): List<ScheduledTaskDto> = taskSchedulingService.listAllTasks().map { it.toDto() }

    override suspend fun listTasksForProject(
        @PathVariable projectId: String,
    ): List<ScheduledTaskDto> = taskSchedulingService.listTasksForProject(ObjectId(projectId)).map { it.toDto() }

    override suspend fun listPendingTasks(): List<ScheduledTaskDto> = taskSchedulingService.listPendingTasks().map { it.toDto() }

    override suspend fun cancelTask(
        @PathVariable taskId: String,
    ) {
        taskSchedulingService.cancelTask(ObjectId(taskId))
    }

    override suspend fun retryTask(
        @PathVariable taskId: String,
    ): ScheduledTaskDto {
        TODO("Not yet implemented")
    }

    override suspend fun updateTaskStatus(
        @PathVariable taskId: String,
        @RequestParam status: String,
        @RequestParam(required = false) errorMessage: String?,
    ): ScheduledTaskDto {
        TODO("Not yet implemented")
    }

    override fun getTasksByStatus(
        @RequestParam taskStatus: ScheduledTaskStatusEnum,
    ): List<ScheduledTaskDto> {
        TODO("Not yet implemented")
    }
}
