package com.jervis.service

import com.jervis.dto.PendingTaskDto
import com.jervis.entity.TaskDocument
import com.jervis.mapper.toPendingTaskDto
import com.jervis.repository.TaskRepository
import com.jervis.types.TaskId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service

@Service
class PendingTaskService(
    private val taskRepository: TaskRepository
) : IPendingTaskService {
    override suspend fun listTasks(
        taskType: String?,
        state: String?,
    ): List<PendingTaskDto> {
        val tasks = if (taskType != null) {
            taskRepository.findByTypeOrderByCreatedAtAsc(com.jervis.dto.TaskTypeEnum.valueOf(taskType))
        } else {
            taskRepository.findAllByOrderByCreatedAtAsc()
        }

        return tasks.map { it.toPendingTaskDto() }.toList()
    }

    override suspend fun countTasks(
        taskType: String?,
        state: String?,
    ): Long {
        return if (taskType != null) {
            taskRepository.countByType(com.jervis.dto.TaskTypeEnum.valueOf(taskType))
        } else {
            taskRepository.count()
        }
    }

    override suspend fun deletePendingTask(id: String) {
        taskRepository.deleteById(TaskId.fromString(id))
    }
}
