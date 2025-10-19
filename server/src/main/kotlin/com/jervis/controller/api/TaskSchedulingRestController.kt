package com.jervis.controller.api

import com.jervis.domain.task.ScheduledTaskStatusEnum
import com.jervis.dto.ScheduleTaskRequestDto
import com.jervis.dto.ScheduledTaskDto
import com.jervis.mapper.toDto
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.scheduling.TaskSchedulingService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class TaskSchedulingRestController(
    private val taskSchedulingService: TaskSchedulingService,
) : ITaskSchedulingService {
    suspend fun scheduleTask(
        @RequestBody request: ScheduleTaskRequestDto,
    ): ScheduledTaskDto =
        taskSchedulingService
            .scheduleTask(
                projectId = ObjectId(request.projectId),
                taskInstruction = request.taskInstruction,
                taskName = request.taskName,
                scheduledAt = Instant.ofEpochMilli(request.scheduledAt),
                taskParameters = request.taskParameters,
                priority = request.priority,
                maxRetries = request.maxRetries,
                cronExpression = request.cronExpression,
                createdBy = request.createdBy,
            ).toDto()

    @DeleteMapping("/cancel/{taskId}")
    override suspend fun cancelTask(
        @PathVariable taskId: String,
    ) {
        taskSchedulingService.cancelTask(ObjectId(taskId))
    }

    override suspend fun retryTask(taskId: String): ScheduledTaskDto {
        TODO("Not yet implemented")
    }

    override suspend fun updateTaskStatus(
        taskId: String,
        status: String,
        errorMessage: String?,
    ): ScheduledTaskDto {
        TODO("Not yet implemented")
    }

    override fun getTasksByStatus(taskStatus: ScheduledTaskStatusEnum): List<ScheduledTaskDto> {
        TODO("Not yet implemented")
    }

    override suspend fun scheduleTask(
        projectId: String,
        taskName: String,
        taskInstruction: String,
        cronExpression: String?,
        priority: Int,
    ): ScheduledTaskDto {
        TODO("Not yet implemented")
    }

    @GetMapping("/{taskId}")
    override suspend fun findById(
        @PathVariable taskId: String,
    ): ScheduledTaskDto? = taskSchedulingService.findById(ObjectId(taskId))?.toDto()

    @GetMapping("/list")
    override suspend fun listAllTasks(): List<ScheduledTaskDto> = taskSchedulingService.listAllTasks().map { it.toDto() }

    @GetMapping("/project/{projectId}")
    override suspend fun listTasksForProject(
        @PathVariable projectId: String,
    ): List<ScheduledTaskDto> = taskSchedulingService.listTasksForProject(ObjectId(projectId)).map { it.toDto() }

    @GetMapping("/pending")
    override suspend fun listPendingTasks(): List<ScheduledTaskDto> = taskSchedulingService.listPendingTasks().map { it.toDto() }
}
