package com.jervis.rpc
 
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.AttachmentDto
import com.jervis.dto.ChatResponseDto
import com.jervis.dto.ChatResponseType
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.service.error.ErrorLogService
import mu.KotlinLogging

import com.jervis.dto.user.TaskRoutingMode
import com.jervis.dto.user.UserTaskCountDto
import com.jervis.dto.user.UserTaskDto
import com.jervis.entity.TaskDocument
import com.jervis.mapper.toDomain
import com.jervis.mapper.toUserTaskDto
import com.jervis.service.IUserTaskService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.service.background.TaskService
import com.jervis.service.task.UserTaskService
import com.jervis.types.ClientId
import com.jervis.types.TaskId
import kotlinx.coroutines.flow.toList
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant
 
@Component
class UserTaskRpcImpl(
    private val userTaskService: UserTaskService,
    private val agentOrchestratorService: AgentOrchestratorService,
    private val taskService: TaskService,
    private val notificationRpc: NotificationRpcImpl,
    private val agentOrchestratorRpc: AgentOrchestratorRpcImpl,
    private val chatMessageRepository: com.jervis.repository.ChatMessageRepository,
    private val taskRepository: com.jervis.repository.TaskRepository,
) : IUserTaskService {
    private val logger = KotlinLogging.logger {}

    override suspend fun listActive(clientId: String): List<UserTaskDto> {
        val cid = ClientId(ObjectId(clientId))
        val tasks = userTaskService.findActiveTasksByClient(cid)
        return tasks.map { it.toUserTaskDto() }
    }

    override suspend fun activeCount(clientId: String): UserTaskCountDto {
        val cid = ClientId(ObjectId(clientId))
        val count = userTaskService.findActiveTasksByClient(cid).size
        return UserTaskCountDto(clientId = clientId, activeCount = count)
    }

    override suspend fun cancel(taskId: String): UserTaskDto {
        val updated = userTaskService.cancelTask(TaskId.fromString(taskId))
        return updated.toUserTaskDto()
    }

    override suspend fun sendToAgent(
        taskId: String,
        routingMode: TaskRoutingMode,
        additionalInput: String?,
        attachments: List<AttachmentDto>,
    ): UserTaskDto {
        val task =
            userTaskService.getTaskById(TaskId.fromString(taskId)) ?: throw IllegalArgumentException("Task not found")

        // Input validation
        if (additionalInput != null && additionalInput.length > 5000) {
            throw IllegalArgumentException("Additional input is too long (max 5000 characters)")
        }

        try {
            when (routingMode) {
                TaskRoutingMode.DIRECT_TO_AGENT -> {
                    // Emit user message to chat stream for UI sync
                    agentOrchestratorRpc.emitToChatStream(
                        clientId = task.clientId.toString(),
                        projectId = task.projectId?.toString() ?: "",
                        response = ChatResponseDto(
                            message = additionalInput ?: "Uživatel pokračuje v úloze: ${task.taskName}",
                            type = ChatResponseType.USER_MESSAGE,
                            metadata = mapOf(
                                "sender" to "user",
                                "clientId" to task.clientId.toString(),
                                "timestamp" to Instant.now().toString(),
                                "resumedFromTask" to task.id.toString()
                            )
                        )
                    )

                    // CRITICAL: Return to SAME TaskDocument, not create new one!
                    // This preserves agent checkpoint and conversation history.
                    // Calculate next queuePosition for FOREGROUND tasks
                    val maxPosition = taskRepository
                        .findByProcessingModeAndStateOrderByQueuePositionAsc(
                            com.jervis.entity.ProcessingMode.FOREGROUND,
                            TaskStateEnum.READY_FOR_GPU
                        )
                        .toList()
                        .maxOfOrNull { it.queuePosition ?: 0 } ?: 0

                    val updatedTask = task.copy(
                        type = TaskTypeEnum.USER_INPUT_PROCESSING,
                        state = TaskStateEnum.READY_FOR_GPU,
                        processingMode = com.jervis.entity.ProcessingMode.FOREGROUND,
                        queuePosition = maxPosition + 1,
                        content = additionalInput ?: "User response: ${task.taskName}",
                        pendingUserQuestion = null,  // Clear question after user responds
                        userQuestionContext = null
                    )
                    taskRepository.save(updatedTask)

                    // Add user response to ChatMessageDocument (conversation history)
                    // Include question context if this was answering a pending question
                    val messageContent = buildString {
                        if (task.pendingUserQuestion != null) {
                            appendLine("Original Question: ${task.pendingUserQuestion}")
                            if (task.userQuestionContext != null) {
                                appendLine("Context: ${task.userQuestionContext}")
                            }
                            appendLine()
                            appendLine("User Answer:")
                        }
                        append(additionalInput ?: "Uživatel pokračuje v úloze: ${task.taskName}")
                    }

                    val messageSequence = chatMessageRepository.countByTaskId(task.id) + 1
                    val userMessage = com.jervis.entity.ChatMessageDocument(
                        taskId = task.id,
                        correlationId = task.correlationId,
                        role = com.jervis.entity.MessageRole.USER,
                        content = messageContent,
                        sequence = messageSequence,
                        timestamp = Instant.now(),
                    )
                    chatMessageRepository.save(userMessage)
                    logger.info { "USER_TASK_RESPONSE_SAVED | taskId=${task.id} | sequence=$messageSequence | processingMode=FOREGROUND | queuePosition=${updatedTask.queuePosition} | answeredQuestion=${task.pendingUserQuestion != null}" }

                    // Notify client that task was removed from user task list
                    notificationRpc.emitUserTaskCancelled(task.clientId.toString(), task.id.toString(), task.taskName)
                }

                TaskRoutingMode.BACK_TO_PENDING -> {
                    // Return task to SAME TaskDocument (BACKGROUND mode for autonomous processing)
                    val updatedTask = task.copy(
                        type = TaskTypeEnum.USER_INPUT_PROCESSING,
                        state = TaskStateEnum.READY_FOR_GPU,
                        processingMode = com.jervis.entity.ProcessingMode.BACKGROUND,
                        queuePosition = null, // BACKGROUND tasks don't use queuePosition
                        content = additionalInput ?: task.content,
                        pendingUserQuestion = null,  // Clear question after user responds
                        userQuestionContext = null
                    )
                    taskRepository.save(updatedTask)

                    // Add user response to ChatMessageDocument if additionalInput provided
                    if (!additionalInput.isNullOrBlank()) {
                        val messageSequence = chatMessageRepository.countByTaskId(task.id) + 1
                        val userMessage = com.jervis.entity.ChatMessageDocument(
                            taskId = task.id,
                            correlationId = task.correlationId,
                            role = com.jervis.entity.MessageRole.USER,
                            content = additionalInput,
                            sequence = messageSequence,
                            timestamp = Instant.now(),
                        )
                        chatMessageRepository.save(userMessage)
                    }

                    logger.info { "USER_TASK_RETURNED_TO_BACKGROUND | taskId=${task.id} | processingMode=BACKGROUND" }

                    // Notify client that task was removed from user task list
                    notificationRpc.emitUserTaskCancelled(task.clientId.toString(), task.id.toString(), task.taskName)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to send task to agent: taskId=$taskId, mode=$routingMode" }
            throw e
        }

        return task.toUserTaskDto()
    }
}
