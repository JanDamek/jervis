package com.jervis.rpc

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
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class UserTaskRpcImpl(
    private val userTaskService: UserTaskService,
) : IUserTaskService {
    override suspend fun listActive(clientId: String): List<UserTaskDto> {
        val cid = ClientId(ObjectId(clientId))
        val tasks = userTaskService.findActiveTasksByClient(cid).toList()
        return tasks.map { it.toDto() }
    }

    override suspend fun activeCount(clientId: String): UserTaskCountDto {
        val cid = ClientId(ObjectId(clientId))
        val count = userTaskService.findActiveTasksByClient(cid).toList().size
        return UserTaskCountDto(clientId = clientId, activeCount = count)
    }

    override suspend fun cancel(taskId: String): UserTaskDto {
        val updated = userTaskService.cancelTask(TaskId.fromString(taskId))
        return updated.toDto()
    }

    override suspend fun sendToAgent(
        taskId: String,
        routingMode: TaskRoutingMode,
        additionalInput: String?,
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
