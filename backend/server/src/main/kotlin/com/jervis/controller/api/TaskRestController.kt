package com.jervis.controller.api

import com.jervis.dto.PendingTaskDto
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.service.IPendingTaskService
import com.jervis.service.background.TaskService
import com.jervis.types.TaskId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.web.bind.annotation.RestController
import java.time.format.DateTimeFormatter

/**
 * REST API for PendingTask management.
 *
 * Note: Context has been removed - all data is now in content field.
 * Tasks are created with complete content, no need for manual updates.
 */
@RestController
class TaskRestController(
    private val taskService: TaskService,
) : IPendingTaskService {
    private val fmt: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    override suspend fun listTasks(
        taskType: String?,
        state: String?,
    ): List<PendingTaskDto> {
        val taskTypeEnum = taskType?.let { runCatching { TaskTypeEnum.valueOf(it) }.getOrNull() }
        val stateEnum = state?.let { runCatching { TaskStateEnum.valueOf(it) }.getOrNull() }

        return taskService
            .findAllTasks(taskTypeEnum, stateEnum)
            .map { task ->
                PendingTaskDto(
                    id = task.id.toString(),
                    taskType = task.type.name,
                    content = task.content,
                    projectId = task.projectId?.toString(),
                    clientId = task.clientId.toString(),
                    createdAt = fmt.format(task.createdAt),
                    state = task.state.name,
                )
            }.toList()
    }

    override suspend fun countTasks(
        taskType: String?,
        state: String?,
    ): Long {
        val taskTypeEnum = taskType?.let { runCatching { TaskTypeEnum.valueOf(it) }.getOrNull() }
        val stateEnum = state?.let { runCatching { TaskStateEnum.valueOf(it) }.getOrNull() }
        return taskService.countTasks(taskTypeEnum, stateEnum)
    }

    override suspend fun deletePendingTask(id: String) = taskService.deleteTaskById(TaskId.fromString(id))
}
