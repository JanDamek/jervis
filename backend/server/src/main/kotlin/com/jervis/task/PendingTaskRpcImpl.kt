package com.jervis.task

import com.jervis.dto.task.PagedPendingTasksResult
import com.jervis.dto.task.PendingTaskDto
import com.jervis.service.task.IPendingTaskService

import com.jervis.infrastructure.error.ErrorLogService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class PendingTaskRpcImpl(
    private val pendingTaskService: com.jervis.task.PendingTaskService,
) : IPendingTaskService {
    override suspend fun listTasks(
        taskType: String?,
        state: String?,
        clientId: String?,
    ): List<PendingTaskDto> = pendingTaskService.listTasks(taskType, state, clientId)

    override suspend fun countTasks(
        taskType: String?,
        state: String?,
        clientId: String?,
    ): Long = pendingTaskService.countTasks(taskType, state, clientId)

    override suspend fun listTasksPaged(
        taskType: String?,
        state: String?,
        page: Int,
        pageSize: Int,
        clientId: String?,
    ): PagedPendingTasksResult = pendingTaskService.listTasksPaged(taskType, state, page, pageSize, clientId)

    override suspend fun deletePendingTask(id: String) = pendingTaskService.deletePendingTask(id)
}
