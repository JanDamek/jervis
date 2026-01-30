package com.jervis.repository

import com.jervis.dto.PendingTaskDto
import com.jervis.service.IPendingTaskService

class PendingTaskRepository(
    private val service: IPendingTaskService,
) : BaseRepository() {
    suspend fun listPendingTasks(
        taskType: String? = null,
        state: String? = null,
    ): List<PendingTaskDto> = safeRpcListCall("listPendingTasks") {
        service.listTasks(taskType, state)
    }

    suspend fun countPendingTasks(
        taskType: String? = null,
        state: String? = null,
    ): Long = safeRpcCall("countPendingTasks") {
        service.countTasks(taskType, state)
    }

    suspend fun deletePendingTask(id: String) {
        safeRpcCall("deletePendingTask") {
            service.deletePendingTask(id)
        }
    }
}
