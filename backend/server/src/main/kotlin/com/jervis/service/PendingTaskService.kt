package com.jervis.service

import com.jervis.dto.PendingTaskDto
import org.springframework.stereotype.Service

@Service
class PendingTaskService : IPendingTaskService {
    override suspend fun listTasks(
        taskType: String?,
        state: String?,
    ): List<PendingTaskDto> = emptyList()

    override suspend fun countTasks(
        taskType: String?,
        state: String?,
    ): Long = 0

    override suspend fun deletePendingTask(id: String) {
        // Not yet implemented
    }
}
