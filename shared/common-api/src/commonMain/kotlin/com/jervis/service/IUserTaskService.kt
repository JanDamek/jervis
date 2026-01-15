package com.jervis.service

import com.jervis.dto.user.TaskRoutingMode
import com.jervis.dto.user.UserTaskCountDto
import com.jervis.dto.user.UserTaskDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IUserTaskService {
    suspend fun listActive(clientId: String): List<UserTaskDto>

    suspend fun activeCount(clientId: String): UserTaskCountDto

    suspend fun cancel(taskId: String): UserTaskDto

    suspend fun sendToAgent(
        taskId: String,
        routingMode: TaskRoutingMode,
        additionalInput: String?,
    ): UserTaskDto
}
