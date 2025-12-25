package com.jervis.controller.api

import com.jervis.dto.user.TaskRoutingMode
import com.jervis.dto.user.UserTaskCountDto
import com.jervis.dto.user.UserTaskDto
import com.jervis.entity.TaskDocument
import com.jervis.service.IUserTaskService
import com.jervis.service.task.UserTaskService
import com.jervis.types.ClientId
import com.jervis.types.TaskId
import kotlinx.coroutines.flow.toList
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/user-tasks")
class UserTaskRestController(
    private val userTaskService: UserTaskService,
) : IUserTaskService {
    @GetMapping("/active")
    override suspend fun listActive(
        @RequestParam clientId: String,
    ): List<UserTaskDto> {
        val cid = ClientId(ObjectId(clientId))
        val tasks = userTaskService.findActiveTasksByClient(cid).toList()
        return tasks.map { it.toDto() }
    }

    @GetMapping("/active-count")
    override suspend fun activeCount(
        @RequestParam clientId: String,
    ): UserTaskCountDto {
        val cid = ClientId(ObjectId(clientId))
        val count = userTaskService.findActiveTasksByClient(cid).toList().size
        return UserTaskCountDto(clientId = clientId, activeCount = count)
    }

    @PutMapping("/cancel")
    override suspend fun cancel(
        @RequestParam taskId: String,
    ): UserTaskDto {
        val updated = userTaskService.cancelTask(TaskId.fromString(taskId))
        return updated.toDto()
    }

    @PostMapping("/send-to-agent")
    override suspend fun sendToAgent(
        @RequestParam taskId: String,
        @RequestParam routingMode: TaskRoutingMode,
        @RequestBody additionalInput: String?,
    ): UserTaskDto {
        val task =
            userTaskService.getTaskById(TaskId.fromString(taskId)) ?: throw IllegalArgumentException("Task not found")
        return task.toDto()
    }
}

private fun TaskDocument.toDto(): UserTaskDto =
    UserTaskDto(
        id = this.id.toString(),
        title = this.taskName,
        description = this.content,
        state = this.state.name,
        projectId = this.projectId?.toString(),
        clientId = this.clientId.toString(),
        sourceUri = this.correlationId,
        createdAtEpochMillis = Instant.now().toEpochMilli(),
    )
