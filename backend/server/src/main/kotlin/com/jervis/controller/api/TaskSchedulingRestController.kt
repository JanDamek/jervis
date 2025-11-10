package com.jervis.controller.api

import com.jervis.domain.task.ScheduledTaskStatusEnum
import com.jervis.dto.ScheduledTaskDto
import com.jervis.mapper.toDto
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.scheduling.TaskSchedulingService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/api/task-scheduling")
class TaskSchedulingRestController(
    private val taskSchedulingService: TaskSchedulingService,
    private val taskManagementService: com.jervis.service.scheduling.TaskManagementService,
    private val scheduledTaskRepository: com.jervis.repository.mongo.ScheduledTaskMongoRepository,
) : ITaskSchedulingService {
    @PostMapping
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

    @DeleteMapping("/{taskId}")
    override suspend fun cancelTask(
        @PathVariable taskId: String,
    ) {
        taskSchedulingService.cancelTask(ObjectId(taskId))
    }

    @PostMapping("/{taskId}/retry")
    override suspend fun retryTask(
        @PathVariable taskId: String,
    ): ScheduledTaskDto {
        val task =
            taskSchedulingService.findById(ObjectId(taskId))
                ?: error("Task not found: $taskId")

        return taskSchedulingService
            .scheduleTask(
                projectId = task.projectId,
                taskInstruction = task.taskInstruction,
                taskName = task.taskName,
                scheduledAt = Instant.now(),
                taskParameters = task.taskParameters,
                priority = task.priority,
                maxRetries = task.maxRetries,
                cronExpression = task.cronExpression,
                createdBy = task.createdBy,
            ).toDto()
    }

    @PutMapping("/{taskId}/status")
    override suspend fun updateTaskStatus(
        @PathVariable taskId: String,
        @RequestParam status: String,
        @RequestParam(required = false) errorMessage: String?,
    ): ScheduledTaskDto {
        val task =
            taskSchedulingService.findById(ObjectId(taskId))
                ?: error("Task not found: $taskId")

        val newStatus = ScheduledTaskStatusEnum.valueOf(status.uppercase())

        return taskManagementService.updateTaskStatus(task, newStatus, errorMessage).toDto()
    }

    @GetMapping("/by-status")
    override fun getTasksByStatus(
        @RequestParam taskStatus: ScheduledTaskStatusEnum,
    ): List<ScheduledTaskDto> = throw UnsupportedOperationException("Use async endpoint instead")
}
