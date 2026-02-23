package com.jervis.service

import com.jervis.common.types.TaskId
import com.jervis.dto.PendingTaskDto
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.mapper.toPendingTaskDto
import com.jervis.repository.TaskRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service

@Service
class PendingTaskService(
    private val taskRepository: TaskRepository,
) : IPendingTaskService {
    override suspend fun listTasks(
        taskType: String?,
        state: String?,
    ): List<PendingTaskDto> {
        val parsedType = taskType?.let { TaskTypeEnum.valueOf(it) }
        val parsedState = state?.let { TaskStateEnum.valueOf(it) }

        val tasks = when {
            parsedType != null && parsedState != null ->
                taskRepository.findByTypeAndStateOrderByCreatedAtAsc(parsedType, parsedState)
            parsedType != null ->
                taskRepository.findByTypeOrderByCreatedAtAsc(parsedType)
            parsedState != null ->
                taskRepository.findByStateOrderByCreatedAtAsc(parsedState)
            else ->
                taskRepository.findAllByOrderByCreatedAtAsc()
        }

        return tasks.map { it.toPendingTaskDto() }.toList()
    }

    override suspend fun countTasks(
        taskType: String?,
        state: String?,
    ): Long {
        val parsedType = taskType?.let { TaskTypeEnum.valueOf(it) }
        val parsedState = state?.let { TaskStateEnum.valueOf(it) }

        return when {
            parsedType != null && parsedState != null ->
                taskRepository.countByTypeAndState(parsedType, parsedState)
            parsedType != null ->
                taskRepository.countByType(parsedType)
            parsedState != null ->
                taskRepository.countByState(parsedState)
            else ->
                taskRepository.count()
        }
    }

    override suspend fun deletePendingTask(id: String) {
        taskRepository.deleteById(TaskId.fromString(id))
    }
}
