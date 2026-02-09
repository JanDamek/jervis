package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.ChatHistoryDto
import com.jervis.dto.ChatMessageDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import com.jervis.dto.ChatResponseType
import com.jervis.mapper.toDomain
import com.jervis.mapper.toDto
import com.jervis.repository.ChatMessageRepository
import com.jervis.repository.TaskRepository
import com.jervis.service.IAgentOrchestratorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class AgentOrchestratorRpcImpl(
    private val chatMessageRepository: ChatMessageRepository,
    private val taskRepository: TaskRepository,
    private val taskService: com.jervis.service.background.TaskService,
    private val projectService: com.jervis.service.project.ProjectService,
) : IAgentOrchestratorService {
    private val logger = KotlinLogging.logger {}
    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val chatStreams = ConcurrentHashMap<String, MutableSharedFlow<ChatResponseDto>>()

    private val queueStatusStreams = ConcurrentHashMap<String, MutableSharedFlow<ChatResponseDto>>()

    /**
     * Internal method to emit a response to a chat stream.
     * Used by BackgroundEngine to send updates during background processing.
     */
    suspend fun emitToChatStream(
        clientId: String,
        projectId: String?,
        response: ChatResponseDto,
    ) {
        val sessionKey = if (projectId.isNullOrBlank()) clientId else "$clientId:$projectId"
        chatStreams[sessionKey]?.emit(response)
    }

    /**
     * Internal method to emit queue status to the client stream.
     * Used by CentralPoller to send queue updates.
     */
    suspend fun emitQueueStatus(
        clientId: String,
        response: ChatResponseDto,
    ) {
        queueStatusStreams[clientId]?.emit(response)
    }

    /**
     * Internal method to emit progress updates to chat stream.
     * Used by BackgroundEngine to send agent progress during execution.
     */
    suspend fun emitProgress(
        clientId: String,
        projectId: String?,
        message: String,
        metadata: Map<String, String>,
    ) {
        val sessionKey = if (projectId.isNullOrBlank()) clientId else "$clientId:$projectId"

        val responseType =
            when (metadata["step"]) {
                "init", "agent_ready" -> ChatResponseType.PLANNING
                "processing", "decomposition", "context", "planning" -> ChatResponseType.PLANNING
                "research", "evidence_gathering" -> ChatResponseType.EVIDENCE_GATHERING
                "executing", "execution", "coding", "tool_execution" -> ChatResponseType.EXECUTING
                "review", "reviewing", "validation" -> ChatResponseType.REVIEWING
                "finalizing", "composing" -> ChatResponseType.EXECUTING
                else -> ChatResponseType.EXECUTING
            }

        val response =
            ChatResponseDto(
                message = message,
                type = responseType,
                metadata = metadata + mapOf("fromProgress" to "true"),
            )

        chatStreams[sessionKey]?.emit(response)
        logger.debug { "PROGRESS_EMITTED | session=$sessionKey | step=${metadata["step"]} | message=${message.take(50)}" }
    }

    override fun subscribeToChat(
        clientId: String,
        projectId: String,
        limit: Int?,
    ): Flow<ChatResponseDto> {
        val sessionKey = if (projectId.isBlank()) clientId else "$clientId:$projectId"
        logger.info { "Client subscribing to chat stream: $sessionKey" }

        val clientIdTyped = ClientId.fromString(clientId)
        val projectIdTyped = if (projectId.isBlank()) null else ProjectId.fromString(projectId)
        val actualLimit = limit ?: 10
        logger.info { "SUBSCRIBE_TO_CHAT | session=$sessionKey | limit=$actualLimit" }

        val sharedFlow =
            chatStreams.getOrPut(sessionKey) {
                MutableSharedFlow(
                    replay = 0,
                    extraBufferCapacity = 100,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }

        return kotlinx.coroutines.flow.flow {
            try {
                val activeTask =
                    taskRepository
                        .findByProcessingModeAndClientIdAndProjectIdAndType(
                            com.jervis.entity.ProcessingMode.FOREGROUND,
                            clientIdTyped,
                            projectIdTyped,
                            com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING,
                        ).toList()
                        .firstOrNull()

                if (activeTask != null) {
                    val messages =
                        chatMessageRepository
                            .findByTaskIdOrderBySequenceAsc(activeTask.id)
                            .toList()
                            .takeLast(actualLimit)
                            .map { it.toDto() }

                    logger.info { "EMITTING_HISTORY | session=$sessionKey | taskId=${activeTask.id} | messages=${messages.size}" }

                    messages.forEach { msg: ChatMessageDto ->
                        val responseType =
                            when (msg.role.lowercase()) {
                                "user" -> ChatResponseType.USER_MESSAGE
                                else -> ChatResponseType.FINAL
                            }

                        emit(
                            ChatResponseDto(
                                message = msg.content,
                                type = responseType,
                                metadata =
                                    mutableMapOf(
                                        "sender" to msg.role,
                                        "timestamp" to msg.timestamp,
                                        "fromHistory" to "true",
                                    ).apply {
                                        msg.correlationId?.let { put("correlationId", it) }
                                    },
                            ),
                        )
                    }
                } else {
                    logger.info { "NO_ACTIVE_TASK_FOUND | session=$sessionKey" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load history for session=$sessionKey" }
            }

            // Emit synchronization marker
            emit(
                ChatResponseDto(
                    message = "HISTORY_LOADED",
                    type = ChatResponseType.FINAL,
                    metadata = mapOf("status" to "synchronized"),
                ),
            )

            // Check if there's a running task for this project and emit its progress
            try {
                val runningTask = taskService.getCurrentRunningTask()
                if (runningTask != null && runningTask.clientId == clientIdTyped && runningTask.projectId == projectIdTyped) {
                    emit(
                        ChatResponseDto(
                            message = "Zpracování probíhá...",
                            type = ChatResponseType.EXECUTING,
                            metadata =
                                mapOf(
                                    "correlationId" to runningTask.correlationId,
                                    "taskId" to runningTask.id.toString(),
                                    "fromRunningTask" to "true",
                                ),
                        ),
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to check running task for session=$sessionKey" }
            }

            // 2. Then emit all LIVE updates from SharedFlow
            sharedFlow.collect { response ->
                emit(response)
            }
        }
    }

    override suspend fun sendMessage(request: ChatRequestDto) {
        val clientId = request.context.clientId
        val projectId = request.context.projectId
        val sessionKey = if (projectId.isNullOrBlank()) clientId else "$clientId:$projectId"
        logger.info { "RPC_MESSAGE_RECEIVED | session=$sessionKey | text='${request.text.take(100)}'" }

        val clientIdTyped = ClientId.fromString(clientId)
        val projectIdTyped = if (projectId.isNullOrBlank()) null else ProjectId.fromString(projectId)

        val stream =
            chatStreams.getOrPut(sessionKey) {
                MutableSharedFlow(
                    replay = 0,
                    extraBufferCapacity = 100,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }

        val timestamp = Instant.now()

        backgroundScope
            .launch {
                stream.emit(
                    ChatResponseDto(
                        message = request.text,
                        type = ChatResponseType.USER_MESSAGE,
                        metadata =
                            mapOf(
                                "sender" to "user",
                                "clientId" to clientId,
                                "timestamp" to timestamp.toString(),
                            ),
                    ),
                )
                logger.info { "USER_MESSAGE_EMITTED | session=$sessionKey | text='${request.text.take(50)}'" }
            }.join() // Wait for emit to complete before continuing

        try {
            logger.info { "PROCESSING_CHAT_MESSAGE | session=$sessionKey" }

            var taskDocument =
                taskRepository
                    .findByProcessingModeAndClientIdAndProjectIdAndType(
                        com.jervis.entity.ProcessingMode.FOREGROUND,
                        clientIdTyped,
                        projectIdTyped,
                        com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING,
                    ).toList()
                    .firstOrNull()

            if (taskDocument == null) {
                // Calculate next queuePosition for FOREGROUND tasks
                val maxPosition =
                    taskRepository
                        .findByProcessingModeAndStateOrderByQueuePositionAsc(
                            com.jervis.entity.ProcessingMode.FOREGROUND,
                            com.jervis.dto.TaskStateEnum.READY_FOR_GPU,
                        ).toList()
                        .maxOfOrNull { it.queuePosition ?: 0 } ?: 0

                // Create NEW FOREGROUND TaskDocument
                taskDocument =
                    com.jervis.entity.TaskDocument(
                        type = com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING,
                        content = request.text,
                        clientId = clientIdTyped,
                        projectId = projectIdTyped,
                        correlationId =
                            org.bson.types
                                .ObjectId()
                                .toString(),
                        sourceUrn = SourceUrn.chat(clientIdTyped),
                        state = com.jervis.dto.TaskStateEnum.READY_FOR_GPU,
                        processingMode = com.jervis.entity.ProcessingMode.FOREGROUND,
                        queuePosition = maxPosition + 1,
                        attachments = request.attachments.map { it.toDomain() },
                    )
                taskDocument = taskRepository.save(taskDocument)
                logger.info {
                    "CREATED_NEW_FOREGROUND_TASK | taskId=${taskDocument.id} | queuePosition=${taskDocument.queuePosition} | session=$sessionKey"
                }
            } else {
                val currentTaskId = taskDocument.id
                val currentState = taskDocument.state
                val currentQueuePos = taskDocument.queuePosition

                val newState =
                    when (currentState) {
                        com.jervis.dto.TaskStateEnum.DISPATCHED_GPU,
                        com.jervis.dto.TaskStateEnum.ERROR,
                        -> {
                            logger.info {
                                "RESETTING_COMPLETED_TASK | taskId=$currentTaskId | oldState=$currentState | session=$sessionKey"
                            }
                            com.jervis.dto.TaskStateEnum.READY_FOR_GPU
                        }

                        com.jervis.dto.TaskStateEnum.READY_FOR_GPU -> {
                            logger.info { "APPENDING_TO_QUEUED_TASK | taskId=$currentTaskId | session=$sessionKey" }
                            com.jervis.dto.TaskStateEnum.READY_FOR_GPU
                        }

                        else -> {
                            logger.warn { "TASK_STILL_PROCESSING | taskId=$currentTaskId | state=$currentState | session=$sessionKey" }
                            currentState
                        }
                    }

                val updatedTask =
                    taskDocument.copy(
                        content = request.text,
                        state = newState,
                    )
                taskDocument = taskRepository.save(updatedTask)
                logger.info {
                    "REUSING_FOREGROUND_TASK | taskId=$currentTaskId | state=$newState | queuePosition=$currentQueuePos | session=$sessionKey"
                }
            }

            val messageSequence = chatMessageRepository.countByTaskId(taskDocument.id) + 1
            val userMessage =
                com.jervis.entity.ChatMessageDocument(
                    taskId = taskDocument.id,
                    correlationId = taskDocument.correlationId,
                    role = com.jervis.entity.MessageRole.USER,
                    content = request.text,
                    sequence = messageSequence,
                    timestamp = timestamp,
                )
            chatMessageRepository.save(userMessage)
            logger.info { "USER_MESSAGE_SAVED | taskId=${taskDocument.id} | sequence=$messageSequence | processingMode=FOREGROUND" }
        } catch (e: Exception) {
            logger.error(e) { "FAILED_TO_PROCESS_CHAT_MESSAGE | session=$sessionKey" }
        }

        // Return after saving - response will arrive via subscribeToChat() stream
        logger.info { "RPC_MESSAGE_ACCEPTED | session=$sessionKey" }
    }

    override suspend fun getChatHistory(
        clientId: String,
        projectId: String?,
        limit: Int,
    ): ChatHistoryDto {
        val clientIdTyped = ClientId.fromString(clientId)
        val projectIdTyped = if (projectId.isNullOrBlank()) null else ProjectId.fromString(projectId)

        logger.info { "CHAT_HISTORY_REQUEST | clientId=$clientId | projectId=$projectId | limit=$limit" }

        val activeTask =
            taskRepository
                .findByProcessingModeAndClientIdAndProjectIdAndType(
                    com.jervis.entity.ProcessingMode.FOREGROUND,
                    clientIdTyped,
                    projectIdTyped,
                    com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING,
                ).toList()
                .firstOrNull()

        if (activeTask == null) {
            logger.info { "CHAT_HISTORY_NOT_FOUND | clientId=$clientId | projectId=$projectId (no active task)" }
            return ChatHistoryDto(messages = emptyList())
        }

        // Get last N messages from ChatMessageDocument collection
        val messages =
            chatMessageRepository
                .findByTaskIdOrderBySequenceAsc(activeTask.id)
                .toList()
                .takeLast(limit)
                .map { it.toDto() }

        logger.info {
            "CHAT_HISTORY_FOUND | clientId=$clientId | projectId=$projectId | taskId=${activeTask.id} | messageCount=${messages.size}"
        }
        return ChatHistoryDto(messages = messages)
    }

    override fun subscribeToQueueStatus(clientId: String): Flow<ChatResponseDto> {
        logger.info { "SUBSCRIBE_TO_QUEUE_STATUS | clientId=$clientId" }

        val sharedFlow =
            queueStatusStreams.getOrPut(clientId) {
                MutableSharedFlow(
                    replay = 1,
                    extraBufferCapacity = 10,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }

        backgroundScope.launch {
            try {
                val clientIdTyped = ClientId.fromString(clientId)

                val (runningTask, queueSize) = taskService.getQueueStatus(clientIdTyped, null)

                // Get pending queue items for display
                val pendingTasks = taskService.getPendingForegroundTasks(clientIdTyped)
                val pendingItems = pendingTasks.mapIndexed { index, task ->
                    val preview = task.content.take(60).let {
                        if (task.content.length > 60) "$it..." else it
                    }
                    val pName = task.projectId?.let { pid ->
                        try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
                    } ?: "General"
                    listOf(
                        "pendingItem_${index}_preview" to preview,
                        "pendingItem_${index}_project" to pName,
                    )
                }.flatten().toMap() + mapOf("pendingItemCount" to pendingTasks.size.toString())

                val response =
                    if (runningTask != null) {
                        val projectName =
                            runningTask.projectId?.let { pid ->
                                try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
                            } ?: "General"
                        val taskPreview =
                            runningTask.content.take(50).let {
                                if (runningTask.content.length > 50) "$it..." else it
                            }
                        val taskTypeLabel =
                            when (runningTask.type) {
                                com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING -> "Asistent"
                                com.jervis.dto.TaskTypeEnum.WIKI_PROCESSING -> "Wiki"
                                com.jervis.dto.TaskTypeEnum.BUGTRACKER_PROCESSING -> "BugTracker"
                                com.jervis.dto.TaskTypeEnum.EMAIL_PROCESSING -> "Email"
                                else -> runningTask.type.toString()
                            }

                        ChatResponseDto(
                            message = "Queue status",
                            type = ChatResponseType.QUEUE_STATUS,
                            metadata =
                                mapOf(
                                    "runningProjectId" to (runningTask.projectId?.toString() ?: "none"),
                                    "runningProjectName" to projectName,
                                    "runningTaskPreview" to taskPreview,
                                    "runningTaskType" to taskTypeLabel,
                                    "queueSize" to queueSize.toString(),
                                ) + pendingItems,
                        )
                    } else {
                        ChatResponseDto(
                            message = if (queueSize > 0) "Queue: $queueSize tasks waiting" else "Queue is empty",
                            type = ChatResponseType.QUEUE_STATUS,
                            metadata =
                                mapOf(
                                    "runningProjectId" to "none",
                                    "runningProjectName" to "None",
                                    "runningTaskPreview" to "",
                                    "runningTaskType" to "",
                                    "queueSize" to queueSize.toString(),
                                ) + pendingItems,
                        )
                    }

                sharedFlow.emit(response)
                logger.info {
                    "INITIAL_QUEUE_STATUS_EMITTED | clientId=$clientId | queueSize=$queueSize | pendingItems=${pendingTasks.size} | hasRunningTask=${runningTask != null} | taskType=${runningTask?.type}"
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to emit initial queue status for clientId=$clientId" }
            }
        }

        return sharedFlow
    }
}
