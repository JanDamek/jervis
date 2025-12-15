package com.jervis.controller.api

import com.jervis.dto.user.TaskRoutingMode
import com.jervis.dto.user.UserTaskCountDto
import com.jervis.dto.user.UserTaskDto
import com.jervis.entity.UserTaskDocument
import com.jervis.service.IUserTaskService
import com.jervis.service.task.UserTaskService
import com.jervis.types.ClientId
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
        val tid = ObjectId(taskId)
        val updated = userTaskService.cancelTask(tid)
        return updated.toDto()
    }

    @PostMapping("/send-to-agent")
    override suspend fun sendToAgent(
        @RequestParam taskId: String,
        @RequestParam routingMode: TaskRoutingMode,
        @RequestBody additionalInput: String?,
    ): UserTaskDto {
        // TODO: Implement full routing logic as per docs/USER_TASK_TO_AGENT_FLOW.md
        // For now, just return the task unchanged
        val tid = ObjectId(taskId)
        val task = userTaskService.getTaskById(tid) ?: throw IllegalArgumentException("Task not found")
        return task.toDto()
    }
}

private fun UserTaskDocument.toDto(): UserTaskDto =
    UserTaskDto(
        id = this.id.toString(),
        title = this.title,
        description = this.description,
        priority = this.priority.name,
        status = this.status.name,
        dueDateEpochMillis = this.dueDate?.toEpochMilli(),
        projectId = this.projectId?.toString(),
        clientId = this.clientId.toString(),
        sourceType = this.sourceType.name,
        sourceUri = this.correlationId,
        createdAtEpochMillis = Instant.now().toEpochMilli(),
    )
