package com.jervis.service

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

    suspend fun deletePendingTask(id: String)
}
