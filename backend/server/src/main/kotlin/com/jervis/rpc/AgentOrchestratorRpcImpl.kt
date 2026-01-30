package com.jervis.rpc

import com.jervis.dto.AttachmentDto
import com.jervis.dto.ChatHistoryDto
import com.jervis.dto.ChatMessageDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import com.jervis.dto.ChatResponseType
import com.jervis.koog.qualifier.KoogQualifierAgent
import com.jervis.mapper.toDomain
import com.jervis.mapper.toDto
import com.jervis.repository.ChatMessageRepository
import com.jervis.repository.TaskRepository
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn
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
    private val agentOrchestratorService: AgentOrchestratorService,
    private val chatMessageRepository: ChatMessageRepository,
    private val taskRepository: TaskRepository,
    private val qualifierAgent: KoogQualifierAgent,
    private val taskService: com.jervis.service.background.TaskService,
) : IAgentOrchestratorService {
    private val logger = KotlinLogging.logger {}
    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Store active chat streams per session (clientId + projectId)
    private val chatStreams = ConcurrentHashMap<String, MutableSharedFlow<ChatResponseDto>>()

    // Store queue status streams per clientId
    private val queueStatusStreams = ConcurrentHashMap<String, MutableSharedFlow<ChatResponseDto>>()

    /**
     * Internal method to emit a response to a chat stream.
     * Used by OrchestratorAgent to send updates during background processing.
     */
    suspend fun emitToChatStream(clientId: String, projectId: String?, response: ChatResponseDto) {
        val sessionKey = if (projectId.isNullOrBlank()) clientId else "$clientId:$projectId"
        chatStreams[sessionKey]?.emit(response)
    }

    /**
     * Internal method to emit queue status to client stream.
     * Used by CentralPoller to send queue updates.
     */
    suspend fun emitQueueStatus(clientId: String, response: ChatResponseDto) {
        queueStatusStreams[clientId]?.emit(response)
    }

    /**
     * Internal method to emit progress updates to chat stream.
     * Used by BackgroundEngine to send agent progress during execution.
     */
    suspend fun emitProgress(clientId: String, projectId: String?, message: String, metadata: Map<String, String>) {
        val sessionKey = if (projectId.isNullOrBlank()) clientId else "$clientId:$projectId"

        // Determine response type based on metadata step
        val responseType = when (metadata["step"]) {
            "init", "agent_ready" -> com.jervis.dto.ChatResponseType.PLANNING
            "processing", "decomposition", "context", "planning" -> com.jervis.dto.ChatResponseType.PLANNING
            "research", "evidence_gathering" -> com.jervis.dto.ChatResponseType.EVIDENCE_GATHERING
            "executing", "execution", "coding", "tool_execution" -> com.jervis.dto.ChatResponseType.EXECUTING
            "review", "reviewing", "validation" -> com.jervis.dto.ChatResponseType.REVIEWING
            "finalizing", "composing" -> com.jervis.dto.ChatResponseType.EXECUTING
            else -> com.jervis.dto.ChatResponseType.EXECUTING
        }

        val response = ChatResponseDto(
            message = message,
            type = responseType,
            metadata = metadata + mapOf("fromProgress" to "true")
        )

        chatStreams[sessionKey]?.emit(response)
        logger.debug { "PROGRESS_EMITTED | session=$sessionKey | step=${metadata["step"]} | message=${message.take(50)}" }
    }

    override fun subscribeToChat(clientId: String, projectId: String, limit: Int?): Flow<ChatResponseDto> {
        val sessionKey = if (projectId.isNullOrBlank()) clientId else "$clientId:$projectId"
        logger.info { "Client subscribing to chat stream: $sessionKey" }

        val clientIdTyped = ClientId.fromString(clientId)
        val projectIdTyped = if (projectId.isNullOrBlank()) null else ProjectId.fromString(projectId)
        val actualLimit = limit ?: 10
        logger.info { "SUBSCRIBE_TO_CHAT | session=$sessionKey | limit=$actualLimit" }

        // Get or create shared flow for this session
        val sharedFlow = chatStreams.getOrPut(sessionKey) {
            MutableSharedFlow(
                replay = 0, // Neukládat historii do bufferu, historii posíláme explicitně při subscribe
                extraBufferCapacity = 100,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }

        // Return flow that FIRST emits history, THEN live updates
        return kotlinx.coroutines.flow.flow {
            // 1. Load and emit chat history IMMEDIATELY when client subscribes
            try {
                // Find active FOREGROUND TaskDocument for this project (chat conversation)
                val activeTask = taskRepository
                    .findByProcessingModeAndClientIdAndProjectIdAndType(
                        com.jervis.entity.ProcessingMode.FOREGROUND,
                        clientIdTyped,
                        projectIdTyped,
                        com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING
                    )
                    .toList()
                    .firstOrNull()

                if (activeTask != null) {
                    // Load last N messages from ChatMessageDocument collection
                    val messages = chatMessageRepository
                        .findByTaskIdOrderBySequenceAsc(activeTask.id)
                        .toList()
                        .takeLast(actualLimit)
                        .map { it.toDto() }

                    logger.info { "EMITTING_HISTORY | session=$sessionKey | taskId=${activeTask.id} | messages=${messages.size}" }

                    messages.forEach { msg: ChatMessageDto ->
                        val responseType = when (msg.role.lowercase()) {
                            "user" -> ChatResponseType.USER_MESSAGE
                            else -> ChatResponseType.FINAL
                        }

                        emit(ChatResponseDto(
                            message = msg.content,
                            type = responseType,
                            metadata = mutableMapOf(
                                "sender" to msg.role,
                                "timestamp" to msg.timestamp.toString(),
                                "fromHistory" to "true"
                            ).apply {
                                msg.correlationId?.let { put("correlationId", it) }
                            }
                        ))
                    }
                } else {
                    logger.info { "NO_ACTIVE_TASK_FOUND | session=$sessionKey" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load history for session=$sessionKey" }
            }

            // Emit synchronization marker
            emit(ChatResponseDto(
                message = "HISTORY_LOADED",
                type = ChatResponseType.FINAL,
                metadata = mapOf("status" to "synchronized")
            ))

            // Check if there's a running task for this project and emit its progress
            try {
                val runningTask = taskService.getCurrentRunningTask()
                if (runningTask != null && runningTask.clientId == clientIdTyped && runningTask.projectId == projectIdTyped) {
                    emit(ChatResponseDto(
                        message = "Zpracování probíhá...",
                        type = ChatResponseType.EXECUTING,
                        metadata = mapOf(
                            "correlationId" to (runningTask.correlationId ?: ""),
                            "taskId" to (runningTask.id?.toString() ?: ""),
                            "fromRunningTask" to "true"
                        )
                    ))
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

        // CRITICAL: Stop any running qualifier agent for this session
        qualifierAgent.cancel(sessionKey)

        val stream = chatStreams.getOrPut(sessionKey) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 100,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }

        // IMMEDIATELY emit user message to ALL clients listening on this session
        // This ensures synchronization across Desktop/iOS/Android
        // IMPORTANT: Must emit synchronously so UI shows user message BEFORE processing starts
        val timestamp = Instant.now()
        
        // Emit user message synchronously in background scope
        backgroundScope.launch {
            stream.emit(ChatResponseDto(
                message = request.text,
                type = ChatResponseType.USER_MESSAGE,
                metadata = mapOf(
                    "sender" to "user",
                    "clientId" to clientId,
                    "timestamp" to timestamp.toString()
                )
            ))
            logger.info { "USER_MESSAGE_EMITTED | session=$sessionKey | text='${request.text.take(50)}'" }
        }.join() // Wait for emit to complete before continuing

        // Find or create FOREGROUND TaskDocument for this project chat (reusable source of truth)
        // Add user message to ChatMessageDocument, set to READY_FOR_GPU
        // IMPORTANT: This MUST be synchronous to prevent race condition with BackgroundEngine
        try {
            logger.info { "PROCESSING_CHAT_MESSAGE | session=$sessionKey" }

            // Find existing FOREGROUND TaskDocument for this project/client, or create new one
            var taskDocument = taskRepository
                .findByProcessingModeAndClientIdAndProjectIdAndType(
                    com.jervis.entity.ProcessingMode.FOREGROUND,
                    clientIdTyped,
                    projectIdTyped,
                    com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING
                )
                .toList()
                .firstOrNull()

            if (taskDocument == null) {
                // Calculate next queuePosition for FOREGROUND tasks
                val maxPosition = taskRepository
                    .findByProcessingModeAndStateOrderByQueuePositionAsc(
                        com.jervis.entity.ProcessingMode.FOREGROUND,
                        com.jervis.dto.TaskStateEnum.READY_FOR_GPU
                    )
                    .toList()
                    .maxOfOrNull { it.queuePosition ?: 0 } ?: 0

                // Create NEW FOREGROUND TaskDocument
                taskDocument = com.jervis.entity.TaskDocument(
                    type = com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING,
                    content = request.text,
                    clientId = clientIdTyped,
                    projectId = projectIdTyped,
                    correlationId = org.bson.types.ObjectId().toString(),
                    sourceUrn = SourceUrn.chat(clientIdTyped),
                    state = com.jervis.dto.TaskStateEnum.READY_FOR_GPU,
                    processingMode = com.jervis.entity.ProcessingMode.FOREGROUND,
                    queuePosition = maxPosition + 1,
                    attachments = request.attachments.map { it.toDomain() }
                )
                taskDocument = taskRepository.save(taskDocument)
                logger.info { "CREATED_NEW_FOREGROUND_TASK | taskId=${taskDocument.id} | queuePosition=${taskDocument.queuePosition} | session=$sessionKey" }
            } else {
                // Reuse existing FOREGROUND TaskDocument - update content and reset to READY_FOR_GPU ONLY if completed
                // This prevents infinite loop where completed task (DISPATCHED_GPU) gets immediately re-queued
                val currentTaskId = taskDocument.id
                val currentState = taskDocument.state
                val currentQueuePos = taskDocument.queuePosition
                
                val newState = when (currentState) {
                    com.jervis.dto.TaskStateEnum.DISPATCHED_GPU,
                    com.jervis.dto.TaskStateEnum.ERROR -> {
                        // Task was completed or failed, safe to reset for new message
                        logger.info { "RESETTING_COMPLETED_TASK | taskId=$currentTaskId | oldState=$currentState | session=$sessionKey" }
                        com.jervis.dto.TaskStateEnum.READY_FOR_GPU
                    }
                    com.jervis.dto.TaskStateEnum.READY_FOR_GPU -> {
                        // Already queued but user sent another message - append to same task
                        logger.info { "APPENDING_TO_QUEUED_TASK | taskId=$currentTaskId | session=$sessionKey" }
                        com.jervis.dto.TaskStateEnum.READY_FOR_GPU
                    }
                    else -> {
                        // Task is still processing (shouldn't happen due to qualifier cancel, but defensive)
                        logger.warn { "TASK_STILL_PROCESSING | taskId=$currentTaskId | state=$currentState | session=$sessionKey" }
                        currentState
                    }
                }
                
                val updatedTask = taskDocument.copy(
                    content = request.text,
                    state = newState
                )
                taskDocument = taskRepository.save(updatedTask)
                logger.info { "REUSING_FOREGROUND_TASK | taskId=$currentTaskId | state=$newState | queuePosition=$currentQueuePos | session=$sessionKey" }
            }

            // Save user message to ChatMessageDocument
            val messageSequence = chatMessageRepository.countByTaskId(taskDocument.id) + 1
            val userMessage = com.jervis.entity.ChatMessageDocument(
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

    override suspend fun getChatHistory(clientId: String, projectId: String?, limit: Int): ChatHistoryDto {
        val sessionKey = if (projectId.isNullOrBlank()) clientId else "$clientId:$projectId"
        val clientIdTyped = ClientId.fromString(clientId)
        val projectIdTyped = if (projectId.isNullOrBlank()) null else ProjectId.fromString(projectId)

        logger.info { "CHAT_HISTORY_REQUEST | clientId=$clientId | projectId=$projectId | limit=$limit" }

        // Find active FOREGROUND TaskDocument for this project (same logic as sendMessage)
        val activeTask = taskRepository
            .findByProcessingModeAndClientIdAndProjectIdAndType(
                com.jervis.entity.ProcessingMode.FOREGROUND,
                clientIdTyped,
                projectIdTyped,
                com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING
            )
            .toList()
            .firstOrNull()

        if (activeTask == null) {
            logger.info { "CHAT_HISTORY_NOT_FOUND | clientId=$clientId | projectId=$projectId (no active task)" }
            return ChatHistoryDto(messages = emptyList())
        }

        // Get last N messages from ChatMessageDocument collection
        val messages = chatMessageRepository
            .findByTaskIdOrderBySequenceAsc(activeTask.id)
            .toList()
            .takeLast(limit)
            .map { it.toDto() }

        logger.info { "CHAT_HISTORY_FOUND | clientId=$clientId | projectId=$projectId | taskId=${activeTask.id} | messageCount=${messages.size}" }
        return ChatHistoryDto(messages = messages)
    }

    override fun subscribeToQueueStatus(clientId: String): Flow<ChatResponseDto> {
        logger.info { "SUBSCRIBE_TO_QUEUE_STATUS | clientId=$clientId" }

        // Get or create shared flow for this client
        val sharedFlow = queueStatusStreams.getOrPut(clientId) {
            MutableSharedFlow(
                replay = 1, // Keep last status for new subscribers
                extraBufferCapacity = 10,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }

        // Immediately emit current queue status for this client
        backgroundScope.launch {
            try {
                val clientIdTyped = ClientId.fromString(clientId)

                // Get queue status for this specific client (uses TaskService which filters by clientId)
                val (runningTask, queueSize) = taskService.getQueueStatus(clientIdTyped, null)

                val response = if (runningTask != null) {
                    // Task is running (FOREGROUND or BACKGROUND)
                    val projectName = "Project"  // Simplified
                    val taskPreview = runningTask.content.take(50).let {
                        if (runningTask.content.length > 50) "$it..." else it
                    }
                    val taskTypeLabel = when (runningTask.type) {
                        com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING -> "Chat"
                        com.jervis.dto.TaskTypeEnum.CONFLUENCE_PROCESSING -> "Confluence"
                        com.jervis.dto.TaskTypeEnum.JIRA_PROCESSING -> "JIRA"
                        com.jervis.dto.TaskTypeEnum.EMAIL_PROCESSING -> "Email"
                        else -> runningTask.type.toString()
                    }

                    ChatResponseDto(
                        message = "Queue status",
                        type = ChatResponseType.QUEUE_STATUS,
                        metadata = mapOf(
                            "runningProjectId" to (runningTask.projectId?.toString() ?: "none"),
                            "runningProjectName" to projectName,
                            "runningTaskPreview" to taskPreview,
                            "runningTaskType" to taskTypeLabel,
                            "queueSize" to queueSize.toString()
                        )
                    )
                } else {
                    // No task running - show queue size (may have queued tasks)
                    ChatResponseDto(
                        message = if (queueSize > 0) "Queue: $queueSize tasks waiting" else "Queue is empty",
                        type = ChatResponseType.QUEUE_STATUS,
                        metadata = mapOf(
                            "runningProjectId" to "none",
                            "runningProjectName" to "None",
                            "runningTaskPreview" to "",
                            "runningTaskType" to "",
                            "queueSize" to queueSize.toString()
                        )
                    )
                }

                sharedFlow.emit(response)
                logger.info { "INITIAL_QUEUE_STATUS_EMITTED | clientId=$clientId | queueSize=$queueSize | hasRunningTask=${runningTask != null} | taskType=${runningTask?.type}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to emit initial queue status for clientId=$clientId" }
            }
        }

        return sharedFlow
    }
}
