package com.jervis.controller.api

import com.jervis.dto.user.UserTaskCountDto
import com.jervis.dto.user.UserTaskDto
import com.jervis.service.IUserTaskService
import com.jervis.service.task.UserTaskService
import kotlinx.coroutines.flow.toList
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneId

@RestController
class UserTaskRestController(
    private val userTaskService: UserTaskService,
) : IUserTaskService {
    override suspend fun listActive(clientId: String): List<UserTaskDto> {
        val cid = ObjectId(clientId)
        val tasks = userTaskService.findActiveTasksByClient(cid).toList()
        return tasks.map { it.toDto() }
    }

    override suspend fun activeCount(clientId: String): UserTaskCountDto {
        val cid = ObjectId(clientId)
        val count = userTaskService.findActiveTasksByClient(cid).toList().size
        return UserTaskCountDto(clientId = clientId, activeCount = count)
    }
}

private fun com.jervis.domain.task.UserTask.toDto(): UserTaskDto =
    UserTaskDto(
        id = this.id.toHexString(),
        title = this.title,
        description = this.description,
        priority = this.priority.name,
        status = this.status.name,
        dueDateEpochMillis = this.dueDate?.toEpochMilli(),
        projectId = this.projectId?.toHexString(),
        clientId = this.clientId.toHexString(),
        sourceType = this.sourceType.name,
        sourceUri = this.sourceUri,
        createdAtEpochMillis =
            this.createdAt
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
    )
