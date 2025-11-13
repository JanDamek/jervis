package com.jervis.repository

import com.jervis.dto.PendingTaskDto
import com.jervis.service.IPendingTaskService

class PendingTaskRepository(
    private val service: IPendingTaskService,
) {
    suspend fun listPendingTasks(): List<PendingTaskDto> = service.listPendingTasks()

    suspend fun deletePendingTask(id: String) {
        service.deletePendingTask(id)
    }
}
