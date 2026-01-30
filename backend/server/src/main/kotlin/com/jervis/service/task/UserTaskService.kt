package com.jervis.service.task

import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.repository.TaskRepository
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn
import com.jervis.types.TaskId
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class UserTaskService(
    private val userTaskRepository: TaskRepository,
    private val notificationRpc: NotificationRpcImpl,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun failAndEscalateToUserTask(
        task: TaskDocument,
        reason: String,
        error: Throwable? = null,
    ) {
        val title = "Background task failed: ${task.type}"
        val description =
            buildString {
                appendLine("Pending task ${task.id} failed in state ${task.state}")
                appendLine("Reason: $reason")
                error?.message?.let { appendLine("Error: $it") }
                appendLine()
                appendLine("Task Content:")
                appendLine(task.content)
            }
        
        // Update existing task to USER_TASK and refresh its content for UI display.
        val updatedTask = task.copy(
            taskName = title,
            content = description,
            state = com.jervis.dto.TaskStateEnum.USER_TASK,
            type = com.jervis.dto.TaskTypeEnum.USER_TASK
        )
        userTaskRepository.save(updatedTask)
        
        // Notify client via kRPC stream
        notificationRpc.emitUserTaskCreated(task.clientId.toString(), task.id.toString(), title)
        
        logger.info { "TASK_FAILED_ESCALATED: id=${task.id} reason=$reason" }
    }

    suspend fun findActiveTasksByClient(clientId: ClientId): List<TaskDocument> =
        userTaskRepository.findAll().toList().filter { 
            it.clientId == clientId && it.type == com.jervis.dto.TaskTypeEnum.USER_TASK 
        }

    suspend fun cancelTask(taskId: TaskId): TaskDocument {
        val task = getTaskByIdOrNull(taskId) ?: throw IllegalArgumentException("Task not found: $taskId")
        userTaskRepository.delete(task)
        notificationRpc.emitUserTaskCancelled(task.clientId.toString(), task.id.toString(), task.taskName)
        logger.info { "TASK_CANCELLED: id=$taskId" }
        return task
    }

    suspend fun getTaskById(taskId: TaskId) = getTaskByIdOrNull(taskId)

    suspend fun getTaskByIdOrNull(taskId: TaskId): TaskDocument? =
        userTaskRepository.findAll().toList().find { it.id == taskId }

    suspend fun deleteTaskById(id: TaskId) {
        getTaskByIdOrNull(id)?.let { userTaskRepository.delete(it) }
    }
}
