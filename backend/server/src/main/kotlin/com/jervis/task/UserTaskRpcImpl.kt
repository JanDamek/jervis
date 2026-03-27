package com.jervis.task

import com.jervis.rpc.NotificationRpcImpl
import com.jervis.common.types.ClientId
import com.jervis.common.types.TaskId
import com.jervis.dto.chat.AttachmentDto
import com.jervis.dto.chat.ChatMessageDto
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.dto.user.TaskRoutingMode
import com.jervis.dto.user.UserTaskCountDto
import com.jervis.dto.user.UserTaskDto
import com.jervis.dto.user.UserTaskListPageDto
import com.jervis.dto.user.UserTaskPageDto
import com.jervis.chat.toDto
import com.jervis.task.toUserTaskDto
import com.jervis.task.toUserTaskListItemDto
import com.jervis.service.task.IUserTaskService
import com.jervis.agent.AgentOrchestratorService
import com.jervis.task.TaskService
import com.jervis.task.UserTaskService
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class UserTaskRpcImpl(
    private val userTaskService: UserTaskService,
    private val agentOrchestratorService: AgentOrchestratorService,
    private val taskService: TaskService,
    private val notificationRpc: NotificationRpcImpl,
    private val chatMessageRepository: com.jervis.chat.ChatMessageRepository,
    private val taskRepository: com.jervis.task.TaskRepository,
    private val chatService: com.jervis.chat.ChatService,
    private val httpClient: HttpClient,
    @Value("\${jervis.o365-browser-pool.url:http://jervis-o365-browser-pool:8090}")
    private val browserPoolUrl: String,
) : IUserTaskService {
    private val logger = KotlinLogging.logger {}

    override suspend fun listActive(clientId: String): List<UserTaskDto> {
        val cid = ClientId(ObjectId(clientId))
        val tasks = userTaskService.findActiveTasksByClient(cid)
        return tasks.map { it.toUserTaskDto() }
    }

    override suspend fun listAll(query: String?, offset: Int, limit: Int): UserTaskPageDto {
        val paged = userTaskService.findPagedTasks(query, offset, limit.coerceAtMost(50))
        return UserTaskPageDto(
            items = paged.items.map { it.toUserTaskDto() },
            totalCount = paged.totalCount,
            hasMore = paged.hasMore,
        )
    }

    override suspend fun listAllLightweight(query: String?, offset: Int, limit: Int): UserTaskListPageDto {
        val paged = userTaskService.findPagedTasksLightweight(query, offset, limit.coerceAtMost(50))
        return UserTaskListPageDto(
            items = paged.items.map { it.toUserTaskListItemDto() },
            totalCount = paged.totalCount,
            hasMore = paged.hasMore,
        )
    }

    override suspend fun getById(taskId: String): UserTaskDto? {
        val task = userTaskService.getTaskById(TaskId.fromString(taskId))
        return task?.toUserTaskDto()
    }

    override suspend fun activeCount(clientId: String): UserTaskCountDto {
        val cid = ClientId(ObjectId(clientId))
        val count = userTaskService.countActiveTasksByClient(cid)
        return UserTaskCountDto(clientId = clientId, activeCount = count.toInt())
    }

    override suspend fun cancel(taskId: String): UserTaskDto {
        val updated = userTaskService.cancelTask(TaskId.fromString(taskId))
        return updated.toUserTaskDto()
    }

    override suspend fun getChatHistory(taskId: String): List<ChatMessageDto> {
        val tid = TaskId.fromString(taskId)
        // Paginate: load last 50 messages instead of ALL (some conversations have thousands)
        return chatMessageRepository.findByConversationIdOrderByIdAsc(tid.value)
            .toList()
            .takeLast(50)
            .map { it.toDto() }
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

        // O365 MFA code forwarding — intercept before regular task routing
        if (task.sourceUrn.value.contains("o365-browser-pool") &&
            !additionalInput.isNullOrBlank() &&
            additionalInput.trim().length <= 10 // MFA codes are short (4-8 digits)
        ) {
            return handleO365MfaResponse(task, additionalInput.trim())
        }

        try {
            when (routingMode) {
                TaskRoutingMode.DIRECT_TO_AGENT -> {
                    // CRITICAL: Return to SAME TaskDocument, not create new one!
                    // This preserves agent checkpoint and conversation history.
                    // Calculate next queuePosition for FOREGROUND tasks
                    val maxPosition =
                        taskRepository
                            .findByProcessingModeAndStateOrderByQueuePositionAsc(
                                com.jervis.task.ProcessingMode.FOREGROUND,
                                TaskStateEnum.QUEUED,
                            ).toList()
                            .maxOfOrNull { it.queuePosition ?: 0 } ?: 0

                    val updatedTask =
                        task.copy(
                            type = TaskTypeEnum.USER_INPUT_PROCESSING,
                            state = TaskStateEnum.QUEUED,
                            processingMode = com.jervis.task.ProcessingMode.FOREGROUND,
                            queuePosition = maxPosition + 1,
                            content = additionalInput ?: "User response: ${task.taskName}",
                            pendingUserQuestion = null, // Clear question after user responds
                            userQuestionContext = null,
                        )
                    taskRepository.save(updatedTask)

                    // Add user response to ChatMessageDocument (conversation history)
                    // Include question context if this was answering a pending question
                    val messageContent =
                        buildString {
                            if (task.pendingUserQuestion != null) {
                                appendLine("Původní dotaz: ${task.pendingUserQuestion}")
                                if (task.userQuestionContext != null) {
                                    appendLine("Kontext: ${task.userQuestionContext}")
                                }
                                appendLine()
                                appendLine("Odpověď uživatele:")
                            }
                            append(additionalInput ?: "Uživatel pokračuje v úloze: ${task.taskName}")
                        }

                    val messageSequence = chatMessageRepository.countByConversationId(task.id.value) + 1
                    val userMessage =
                        com.jervis.chat.ChatMessageDocument(
                            conversationId = task.id.value,
                            correlationId = task.correlationId,
                            role = com.jervis.chat.MessageRole.USER,
                            content = messageContent,
                            sequence = messageSequence,
                            timestamp = Instant.now(),
                        )
                    chatMessageRepository.save(userMessage)
                    logger.info {
                        "USER_TASK_RESPONSE_SAVED | taskId=${task.id} | sequence=$messageSequence | processingMode=FOREGROUND | queuePosition=${updatedTask.queuePosition} | answeredQuestion=${task.pendingUserQuestion != null}"
                    }

                    // Notify client that task was removed from user task list
                    notificationRpc.emitUserTaskCancelled(task.clientId.toString(), task.id.toString(), task.taskName)
                }

                TaskRoutingMode.BACK_TO_PENDING -> {
                    // Return task to SAME TaskDocument (BACKGROUND mode for autonomous processing)
                    val updatedTask =
                        task.copy(
                            type = TaskTypeEnum.USER_INPUT_PROCESSING,
                            state = TaskStateEnum.QUEUED,
                            processingMode = com.jervis.task.ProcessingMode.BACKGROUND,
                            queuePosition = null, // BACKGROUND tasks don't use queuePosition
                            content = additionalInput ?: task.content,
                            pendingUserQuestion = null, // Clear question after user responds
                            userQuestionContext = null,
                        )
                    taskRepository.save(updatedTask)

                    // Add user response to ChatMessageDocument if additionalInput provided
                    if (!additionalInput.isNullOrBlank()) {
                        val messageSequence = chatMessageRepository.countByConversationId(task.id.value) + 1
                        val userMessage =
                            com.jervis.chat.ChatMessageDocument(
                                conversationId = task.id.value,
                                correlationId = task.correlationId,
                                role = com.jervis.chat.MessageRole.USER,
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

    override suspend fun dismiss(taskId: String) {
        var dismissed = false

        // 1. Try to dismiss as TaskDocument (any active state → DONE)
        try {
            val task = taskRepository.getById(TaskId.fromString(taskId))
            if (task != null && task.state != TaskStateEnum.DONE && task.state != TaskStateEnum.ERROR) {
                val updated = task.copy(state = TaskStateEnum.DONE)
                taskRepository.save(updated)
                logger.info { "USER_TASK_DISMISSED | taskId=$taskId | previousState=${task.state} | title=${task.taskName}" }
                notificationRpc.emitUserTaskCancelled(task.clientId.toString(), task.id.toString(), task.taskName)
                dismissed = true
            }
        } catch (_: Exception) {
            // ID might not be a valid TaskId — continue to chat message lookup
        }

        // 2. Dismiss BACKGROUND chat messages referencing this taskId in metadata
        val chatMessages = chatMessageRepository.findByMetadataTaskId(taskId).toList()
        for (msg in chatMessages) {
            if (msg.metadata["needsReaction"] == "true" || msg.metadata["success"] == "false") {
                val updatedMetadata = msg.metadata.toMutableMap()
                updatedMetadata["needsReaction"] = "false"
                updatedMetadata["success"] = "true"
                updatedMetadata["dismissed"] = "true"
                chatMessageRepository.save(msg.copy(metadata = updatedMetadata))
                logger.info { "CHAT_MESSAGE_DISMISSED | messageId=${msg.id} | taskId=$taskId" }
                dismissed = true
            }
        }

        // 3. Try to dismiss as a chat message directly by its ObjectId (messageId fallback)
        if (!dismissed) {
            try {
                val msg = chatMessageRepository.findById(ObjectId(taskId))
                if (msg != null && (msg.metadata["needsReaction"] == "true" || msg.metadata["success"] == "false")) {
                    val updatedMetadata = msg.metadata.toMutableMap()
                    updatedMetadata["needsReaction"] = "false"
                    updatedMetadata["success"] = "true"
                    updatedMetadata["dismissed"] = "true"
                    chatMessageRepository.save(msg.copy(metadata = updatedMetadata))
                    logger.info { "CHAT_MESSAGE_DISMISSED_BY_ID | messageId=$taskId" }
                    dismissed = true
                }
            } catch (_: Exception) {
                // Not a valid ObjectId — skip
            }
        }

        if (!dismissed) {
            logger.warn { "DISMISS_SKIP | id=$taskId — no task or chat message found" }
        }
    }

    override suspend fun dismissAll(): Int {
        var count = 0

        // 1. Dismiss all USER_TASK tasks (type=USER_TASK, state=USER_TASK → DONE)
        val tasks = taskRepository.findByTypeAndStateOrderByCreatedAtAsc(TaskTypeEnum.USER_TASK, TaskStateEnum.USER_TASK).toList()
        for (task in tasks) {
            val updated = task.copy(state = TaskStateEnum.DONE)
            taskRepository.save(updated)
            notificationRpc.emitUserTaskCancelled(task.clientId.toString(), task.id.toString(), task.taskName)
            count++
        }

        // 2. Dismiss actionable BACKGROUND chat messages (needsReaction=true or success=false)
        val bgCount = chatService.dismissAllActionableBackground()
        count += bgCount

        logger.info { "USER_TASK_DISMISS_ALL | tasks=${tasks.size} | backgroundMessages=$bgCount | total=$count" }
        return count
    }

    override suspend fun respondToTask(taskId: String, response: String) {
        val task = taskRepository.getById(TaskId.fromString(taskId))
            ?: throw IllegalArgumentException("Task not found: $taskId")

        // Queue for orchestrator — extracts behavioral rules from user response, stores in KB
        val updated = task.copy(
            state = TaskStateEnum.QUEUED,
            content = "${task.content}\n\n[User response]: $response",
            pendingUserQuestion = null,
        )
        taskRepository.save(updated)

        // Record in conversation history
        val messageSequence = chatMessageRepository.countByConversationId(task.id.value) + 1
        val userMessage = com.jervis.chat.ChatMessageDocument(
            conversationId = task.id.value,
            correlationId = task.correlationId,
            role = com.jervis.chat.MessageRole.USER,
            content = response,
            sequence = messageSequence,
            timestamp = Instant.now(),
        )
        chatMessageRepository.save(userMessage)

        logger.info { "TASK_INLINE_RESPONSE | taskId=$taskId | state=INDEXING" }

        // Notify client that task state changed
        notificationRpc.emitUserTaskCancelled(task.clientId.toString(), task.id.toString(), task.taskName)
    }

    /**
     * Forward MFA code to O365 browser pool and mark the task as done.
     * This is called when a user responds to an O365 MFA UserTask notification.
     * The code is forwarded to the browser pool's /session/{clientId}/mfa endpoint.
     * For authenticator_number type (no code needed), user just approves in Authenticator app —
     * the browser pool polling loop handles that automatically.
     */
    private suspend fun handleO365MfaResponse(
        task: com.jervis.task.TaskDocument,
        mfaCode: String,
    ): UserTaskDto {
        // actionType stores the browser pool client ID (= connection._id)
        val browserPoolClientId = task.actionType
            ?: throw IllegalStateException("O365 MFA task missing browser pool client ID")

        logger.info { "O365_MFA_RESPONSE | taskId=${task.id} | browserPoolClient=$browserPoolClientId | forwarding code" }

        try {
            val response = httpClient.post("$browserPoolUrl/session/$browserPoolClientId/mfa") {
                contentType(ContentType.Application.Json)
                setBody("""{"code":"$mfaCode"}""")
            }

            val responseText = response.bodyAsText()
            logger.info { "O365_MFA_FORWARD | status=${response.status} | response=$responseText" }

            // Mark the MFA task as done regardless of result
            // (browser pool will send new notification if MFA fails)
            val updated = task.copy(state = TaskStateEnum.DONE)
            taskRepository.save(updated)
            notificationRpc.emitUserTaskCancelled(task.clientId.toString(), task.id.toString(), task.taskName)

        } catch (e: Exception) {
            logger.error(e) { "O365_MFA_FORWARD_FAILED | taskId=${task.id}" }
            // Still mark as done — browser pool will re-notify if needed
            val updated = task.copy(state = TaskStateEnum.DONE)
            taskRepository.save(updated)
            notificationRpc.emitUserTaskCancelled(task.clientId.toString(), task.id.toString(), task.taskName)
        }

        return task.toUserTaskDto()
    }
}
