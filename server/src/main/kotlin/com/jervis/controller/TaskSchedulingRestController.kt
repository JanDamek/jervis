package com.jervis.controller

import com.jervis.dto.ScheduleTaskRequestDto
import com.jervis.dto.ScheduledTaskDto
import com.jervis.mapper.toDto
import com.jervis.service.scheduling.TaskSchedulingService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/task-scheduling")
class TaskSchedulingRestController(
    private val taskSchedulingService: TaskSchedulingService,
) {
    @PostMapping("/schedule")
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
    suspend fun cancelTask(
        @PathVariable taskId: String,
    ): Boolean = taskSchedulingService.cancelTask(ObjectId(taskId))

    @GetMapping("/{taskId}")
    suspend fun findById(
        @PathVariable taskId: String,
    ): ScheduledTaskDto? = taskSchedulingService.findById(ObjectId(taskId))?.toDto()

    @GetMapping("/list")
    suspend fun listAllTasks(): List<ScheduledTaskDto> = taskSchedulingService.listAllTasks().map { it.toDto() }

    @GetMapping("/project/{projectId}")
    suspend fun listTasksForProject(
        @PathVariable projectId: String,
    ): List<ScheduledTaskDto> = taskSchedulingService.listTasksForProject(ObjectId(projectId)).map { it.toDto() }

    @GetMapping("/pending")
    suspend fun listPendingTasks(): List<ScheduledTaskDto> = taskSchedulingService.listPendingTasks().map { it.toDto() }
}
