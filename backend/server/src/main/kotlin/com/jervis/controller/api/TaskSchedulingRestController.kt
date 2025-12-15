package com.jervis.controller.api

import com.jervis.dto.ScheduledTaskDto
import com.jervis.mapper.toDto
import com.jervis.service.ITaskSchedulingService
import com.jervis.service.scheduling.TaskSchedulingService
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/task-scheduling")
class TaskSchedulingRestController(
    private val taskSchedulingService: TaskSchedulingService,
) : ITaskSchedulingService {
    @PostMapping
    override suspend fun scheduleTask(
        @RequestParam clientId: String,
        @RequestParam projectId: String?,
        @RequestParam taskName: String,
        @RequestParam content: String,
        @RequestParam(required = false) cronExpression: String?,
        @RequestParam(required = false) correlationId: String?,
    ): ScheduledTaskDto =
        taskSchedulingService
            .scheduleTask(
                clientId = ClientId(ObjectId(clientId)),
                projectId = projectId?.let { ProjectId(ObjectId(it)) },
                content = content,
                taskName = taskName,
                scheduledAt = Instant.now(),
                cronExpression = cronExpression,
                correlationId = correlationId,
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

    @GetMapping("/client/{clientId}")
    override suspend fun listTasksForClient(
        @PathVariable clientId: String,
    ): List<ScheduledTaskDto> = taskSchedulingService.listTasksForClient(ObjectId(clientId)).map { it.toDto() }

    @DeleteMapping("/{taskId}")
    override suspend fun cancelTask(
        @PathVariable taskId: String,
    ) {
        taskSchedulingService.cancelTask(ObjectId(taskId))
    }
}
