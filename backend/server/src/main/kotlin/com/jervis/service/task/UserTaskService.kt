package com.jervis.service.task

import com.jervis.common.types.ClientId
import com.jervis.common.types.TaskId
import com.jervis.entity.TaskDocument
import com.jervis.repository.TaskRepository
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.service.notification.FcmPushService
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class UserTaskService(
    private val userTaskRepository: TaskRepository,
    private val notificationRpc: NotificationRpcImpl,
    private val fcmPushService: FcmPushService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun failAndEscalateToUserTask(
        task: TaskDocument,
        reason: String,
        error: Throwable? = null,
        pendingQuestion: String? = null,
        questionContext: String? = null,
        interruptAction: String? = null,
        isApproval: Boolean = false,
    ) {
        val title = pendingQuestion ?: "Background task failed: ${task.type}"
        val description =
            buildString {
                if (pendingQuestion != null) {
                    appendLine("Agent Question:")
                    appendLine(pendingQuestion)
                    appendLine()
                    if (questionContext != null) {
                        appendLine("Context:")
                        appendLine(questionContext)
                        appendLine()
                    }
                    appendLine("Original Task:")
                    appendLine(task.content)
                } else {
                    appendLine("Pending task ${task.id} failed in state ${task.state}")
                    appendLine("Reason: $reason")
                    error?.message?.let { appendLine("Error: $it") }
                    appendLine()
                    appendLine("Task Content:")
                    appendLine(task.content)
                }
            }

        // Update existing task to USER_TASK and refresh its content for UI display.
        val updatedTask =
            task.copy(
                taskName = title,
                content = description,
                state = com.jervis.dto.TaskStateEnum.USER_TASK,
                type = com.jervis.dto.TaskTypeEnum.USER_TASK,
                pendingUserQuestion = pendingQuestion,
                userQuestionContext = questionContext,
            )
        userTaskRepository.save(updatedTask)

        // Notify client via kRPC stream (with approval metadata for push notifications)
        notificationRpc.emitUserTaskCreated(
            clientId = task.clientId.toString(),
            taskId = task.id.toString(),
            title = title,
            interruptAction = interruptAction,
            interruptDescription = pendingQuestion,
            isApproval = isApproval,
        )

        // Send FCM push notification for mobile devices not connected via kRPC
        try {
            fcmPushService.sendPushNotification(
                clientId = task.clientId.toString(),
                title = if (isApproval) "Schválení vyžadováno" else "Nová úloha",
                body = title,
                data = buildMap {
                    put("taskId", task.id.toString())
                    put("type", if (isApproval) "approval" else "user_task")
                    interruptAction?.let { put("interruptAction", it) }
                    put("isApproval", isApproval.toString())
                },
            )
        } catch (e: Exception) {
            logger.warn { "FCM push failed for task ${task.id}: ${e.message}" }
        }

        logger.info { "TASK_FAILED_ESCALATED: id=${task.id} reason=$reason pendingQuestion=${pendingQuestion != null}" }
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

    suspend fun getTaskByIdOrNull(taskId: TaskId): TaskDocument? = userTaskRepository.getById(taskId)

    suspend fun deleteTaskById(id: TaskId) {
        getTaskByIdOrNull(id)?.let { userTaskRepository.delete(it) }
    }
}
