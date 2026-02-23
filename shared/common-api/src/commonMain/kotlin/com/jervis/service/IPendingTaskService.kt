package com.jervis.service

import com.jervis.dto.PagedPendingTasksResult
import com.jervis.dto.PendingTaskDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IPendingTaskService {
    suspend fun listTasks(
        taskType: String? = null,
        state: String? = null,
    ): List<PendingTaskDto>

    suspend fun countTasks(
        taskType: String? = null,
        state: String? = null,
    ): Long

    /** Paginated list + count in a single RPC call. Replaces parallel listTasks + countTasks. */
    suspend fun listTasksPaged(
        taskType: String? = null,
        state: String? = null,
        page: Int = 0,
        pageSize: Int = 50,
    ): PagedPendingTasksResult

    suspend fun deletePendingTask(id: String)
}
