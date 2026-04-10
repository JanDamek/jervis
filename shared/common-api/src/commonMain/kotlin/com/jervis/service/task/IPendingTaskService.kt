package com.jervis.service.task

import com.jervis.dto.task.PagedPendingTasksResult
import com.jervis.dto.task.PendingTaskDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IPendingTaskService {
    suspend fun listTasks(
        taskType: String? = null,
        state: String? = null,
        clientId: String? = null,
    ): List<PendingTaskDto>

    suspend fun countTasks(
        taskType: String? = null,
        state: String? = null,
        clientId: String? = null,
    ): Long

    /** Paginated list + count in a single RPC call. Replaces parallel listTasks + countTasks. */
    suspend fun listTasksPaged(
        taskType: String? = null,
        state: String? = null,
        page: Int = 0,
        pageSize: Int = 50,
        clientId: String? = null,
    ): PagedPendingTasksResult

    suspend fun deletePendingTask(id: String)
}
