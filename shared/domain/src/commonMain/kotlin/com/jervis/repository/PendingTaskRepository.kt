package com.jervis.repository

import com.jervis.dto.PendingTaskDto
import com.jervis.service.IPendingTaskService

class PendingTaskRepository(
    private val service: IPendingTaskService,
) {
    suspend fun listPendingTasks(
        taskType: String? = null,
        state: String? = null,
    ): List<PendingTaskDto> = service.listTasks(taskType, state)

    suspend fun countPendingTasks(
        taskType: String? = null,
        state: String? = null,
    ): Long = service.countTasks(taskType, state)

    suspend fun deletePendingTask(id: String) {
        service.deletePendingTask(id)
    }
}
