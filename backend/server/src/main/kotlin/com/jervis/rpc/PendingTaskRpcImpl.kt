package com.jervis.rpc

import com.jervis.dto.PendingTaskDto
import com.jervis.service.IPendingTaskService

import com.jervis.service.error.ErrorLogService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class PendingTaskRpcImpl(
    private val pendingTaskService: com.jervis.service.PendingTaskService,
) : IPendingTaskService {
    override suspend fun listTasks(
        taskType: String?,
        state: String?,
    ): List<PendingTaskDto> = pendingTaskService.listTasks(taskType, state)

    override suspend fun countTasks(
        taskType: String?,
        state: String?,
    ): Long = pendingTaskService.countTasks(taskType, state)

    override suspend fun deletePendingTask(id: String) = pendingTaskService.deletePendingTask(id)
}
