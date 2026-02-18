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
import com.jervis.dto.CompressionBoundaryDto
import com.jervis.repository.ChatMessageRepository
import com.jervis.repository.ChatSummaryRepository
import com.jervis.repository.TaskHistoryRepository
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
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Component
class AgentOrchestratorRpcImpl(
    private val chatMessageRepository: ChatMessageRepository,
    private val chatSummaryRepository: ChatSummaryRepository,
    private val taskRepository: TaskRepository,
    private val taskHistoryRepository: TaskHistoryRepository,
    private val taskService: com.jervis.service.background.TaskService,
    private val projectService: com.jervis.service.project.ProjectService,
    private val taskNotifier: com.jervis.service.background.TaskNotifier,
    private val pythonOrchestratorClient: com.jervis.configuration.PythonOrchestratorClient,
) : IAgentOrchestratorService {
    private val logger = KotlinLogging.logger {}
    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Value("\${jervis.attachments.storage-dir:#{systemProperties['java.io.tmpdir']}/jervis-attachments}")
    private lateinit var attachmentStorageDir: String

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
     * Emit queue status to ALL connected client streams.
     * Queue/agent status is global — all clients see the same orchestrator state.
     */
    suspend fun emitQueueStatusToAll(response: ChatResponseDto) {
        for ((_, stream) in queueStatusStreams) {
            stream.emit(response)
        }
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

                    // Load full message documents to access metadata (workflow steps)
                    val messageDocuments =
                        chatMessageRepository
                            .findByTaskIdOrderBySequenceAsc(activeTask.id)
                            .toList()
                            .takeLast(actualLimit)

                    messageDocuments.forEach { msgDoc ->
                        val responseType =
                            when (msgDoc.role) {
                                com.jervis.entity.MessageRole.USER -> ChatResponseType.USER_MESSAGE
                                else -> ChatResponseType.FINAL
                            }

                        emit(
                            ChatResponseDto(
                                message = msgDoc.content,
                                type = responseType,
                                metadata =
                                    mutableMapOf(
                                        "sender" to msgDoc.role.name.lowercase(),
                                        "timestamp" to msgDoc.timestamp.toString(),
                                        "fromHistory" to "true",
                                    ).apply {
                                        put("correlationId", msgDoc.correlationId)
                                        // Include workflow steps if present
                                        msgDoc.metadata["workflowSteps"]?.let { workflowStepsJson ->
                                            put("workflowSteps", workflowStepsJson)
                                        }
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
        logger.info { "RPC_MESSAGE_RECEIVED | session=$sessionKey | isHistoryReplay=${request.isHistoryReplay} | text='${request.text.take(100)}'" }

        val clientIdTyped = ClientId.fromString(clientId)
        val projectIdTyped = if (projectId.isNullOrBlank()) null else ProjectId.fromString(projectId)

        // History replay: save message to DB for display, but do NOT trigger task processing
        if (request.isHistoryReplay) {
            logger.info { "HISTORY_REPLAY_MESSAGE | session=$sessionKey | skipping task processing" }
            // Just save to chat history and return - no task state changes
            val taskDoc = taskRepository
                .findByProcessingModeAndClientIdAndProjectIdAndType(
                    com.jervis.entity.ProcessingMode.FOREGROUND,
                    clientIdTyped,
                    projectIdTyped,
                    com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING,
                ).toList()
                .firstOrNull()

            if (taskDoc != null) {
                val sequence = chatMessageRepository.countByTaskId(taskDoc.id) + 1
                chatMessageRepository.save(
                    com.jervis.entity.ChatMessageDocument(
                        taskId = taskDoc.id,
                        correlationId = taskDoc.correlationId,
                        role = com.jervis.entity.MessageRole.USER,
                        content = request.text,
                        sequence = sequence,
                    ),
                )
                logger.info { "HISTORY_REPLAY_SAVED | taskId=${taskDoc.id} | sequence=$sequence" }
            }
            return
        }

        // Dedup check: skip if this exact message was already processed (idempotent retry)
        val clientMsgId = request.clientMessageId
        if (!clientMsgId.isNullOrBlank()) {
            if (chatMessageRepository.existsByClientMessageId(clientMsgId)) {
                logger.info { "DEDUP_SKIP | clientMessageId=$clientMsgId | session=$sessionKey" }
                return
            }
        }

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
                        attachments = request.attachments.map { saveAndMapAttachment(it) },
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
                        com.jervis.dto.TaskStateEnum.READY_FOR_QUALIFICATION,
                        com.jervis.dto.TaskStateEnum.QUALIFYING,
                        -> {
                            // FOREGROUND chat tasks skip qualification — go directly to GPU
                            logger.info {
                                "RESETTING_TASK_TO_GPU | taskId=$currentTaskId | oldState=$currentState | session=$sessionKey"
                            }
                            com.jervis.dto.TaskStateEnum.READY_FOR_GPU
                        }

                        com.jervis.dto.TaskStateEnum.READY_FOR_GPU -> {
                            logger.info { "APPENDING_TO_QUEUED_TASK | taskId=$currentTaskId | session=$sessionKey" }
                            com.jervis.dto.TaskStateEnum.READY_FOR_GPU
                        }

                        com.jervis.dto.TaskStateEnum.PYTHON_ORCHESTRATING -> {
                            // Agent is running on Python orchestrator for this project.
                            // Save message to chat history (below); auto-requeue after orchestration completes.
                            logger.info { "INLINE_MESSAGE_QUEUED | taskId=$currentTaskId | state=PYTHON_ORCHESTRATING | session=$sessionKey" }
                            currentState // keep state unchanged - let orchestrator finish
                        }

                        else -> {
                            // For any other state (RUNNING, etc.), force reset to READY_FOR_GPU
                            logger.warn { "FORCE_RESETTING_TASK | taskId=$currentTaskId | oldState=$currentState | session=$sessionKey" }
                            com.jervis.dto.TaskStateEnum.READY_FOR_GPU
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

            // Wake up execution loop immediately if task is ready for processing
            if (taskDocument.state == com.jervis.dto.TaskStateEnum.READY_FOR_GPU) {
                taskNotifier.notifyNewTask()
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
                    clientMessageId = request.clientMessageId,
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
        beforeSequence: Long?,
    ): ChatHistoryDto {
        val clientIdTyped = ClientId.fromString(clientId)
        val projectIdTyped = if (projectId.isNullOrBlank()) null else ProjectId.fromString(projectId)

        logger.info { "CHAT_HISTORY_REQUEST | clientId=$clientId | projectId=$projectId | limit=$limit | beforeSequence=$beforeSequence" }

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

        // Fetch limit+1 to determine if there are more messages
        val fetchLimit = limit + 1
        val rawMessages = if (beforeSequence != null) {
            chatMessageRepository
                .findByTaskIdAndSequenceLessThanOrderBySequenceDesc(activeTask.id, beforeSequence)
                .toList()
                .take(fetchLimit)
        } else {
            chatMessageRepository
                .findByTaskIdOrderBySequenceAsc(activeTask.id)
                .toList()
                .takeLast(fetchLimit)
        }

        val hasMore = rawMessages.size > limit
        val trimmedMessages = if (hasMore) {
            if (beforeSequence != null) {
                // Desc order from repo, trim oldest (last in list), then reverse
                rawMessages.dropLast(1)
            } else {
                // Asc order, trim oldest (first in list)
                rawMessages.drop(1)
            }
        } else {
            rawMessages
        }

        // Ensure ascending order for UI display
        val messages = if (beforeSequence != null) {
            trimmedMessages.sortedBy { it.sequence }.map { it.toDto() }
        } else {
            trimmedMessages.map { it.toDto() }
        }

        val oldestSequence = messages.firstOrNull()?.sequence

        // Load compression boundaries that overlap with visible message range
        val compressionBoundaries = try {
            val summaries = chatSummaryRepository
                .findByTaskIdOrderBySequenceEndAsc(activeTask.id)
                .toList()

            val minSeq = messages.firstOrNull()?.sequence ?: 0L
            val maxSeq = messages.lastOrNull()?.sequence ?: Long.MAX_VALUE

            summaries
                .filter { it.sequenceEnd >= minSeq && it.sequenceEnd <= maxSeq }
                .map { summary ->
                    CompressionBoundaryDto(
                        afterSequence = summary.sequenceEnd,
                        summary = summary.summary,
                        compressedMessageCount = summary.messageCount,
                        topics = summary.topics,
                    )
                }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load compression boundaries for taskId=${activeTask.id}" }
            emptyList()
        }

        logger.info {
            "CHAT_HISTORY_FOUND | clientId=$clientId | projectId=$projectId | taskId=${activeTask.id} | messageCount=${messages.size} | hasMore=$hasMore | compressionBoundaries=${compressionBoundaries.size}"
        }
        return ChatHistoryDto(
            messages = messages,
            hasMore = hasMore,
            oldestSequence = oldestSequence,
            compressionBoundaries = compressionBoundaries,
        )
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

        // Emit initial global queue status
        backgroundScope.launch {
            try {
                val response = buildGlobalQueueStatusResponse()
                sharedFlow.emit(response)
                logger.info {
                    "INITIAL_QUEUE_STATUS_EMITTED | clientId=$clientId | ${response.metadata}"
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to emit initial queue status for clientId=$clientId" }
            }
        }

        return sharedFlow
    }

    // --- Queue Management RPC Methods ---

    override suspend fun getPendingTasks(clientId: String): com.jervis.dto.PendingTasksDto {
        logger.info { "GET_PENDING_TASKS | clientId=$clientId (global)" }

        val running = taskService.getCurrentRunningTask()

        val foregroundTasks = taskService.getPendingForegroundTasks()
        val (backgroundTasks, backgroundTotal) = taskService.getPendingBackgroundTasksPaginated(limit = 20, offset = 0)

        val foregroundItems = foregroundTasks.map { task -> task.toPendingTaskItemDto() }
        val backgroundItems = backgroundTasks.map { task -> task.toPendingTaskItemDto() }

        val runningItem = running?.toPendingTaskItemDto()

        logger.info {
            "PENDING_TASKS_RESULT | clientId=$clientId | foreground=${foregroundItems.size} | background=${backgroundItems.size} | backgroundTotal=$backgroundTotal | hasRunning=${runningItem != null}"
        }

        return com.jervis.dto.PendingTasksDto(
            foreground = foregroundItems,
            background = backgroundItems,
            runningTask = runningItem,
            backgroundTotalCount = backgroundTotal,
        )
    }

    override suspend fun getBackgroundTasksPage(limit: Int, offset: Int): com.jervis.dto.PendingTasksPageDto {
        logger.info { "GET_BACKGROUND_TASKS_PAGE | limit=$limit | offset=$offset" }

        val (tasks, totalCount) = taskService.getPendingBackgroundTasksPaginated(limit, offset)
        val items = tasks.map { task -> task.toPendingTaskItemDto() }

        logger.info { "BACKGROUND_TASKS_PAGE_RESULT | items=${items.size} | totalCount=$totalCount | offset=$offset" }

        return com.jervis.dto.PendingTasksPageDto(
            items = items,
            totalCount = totalCount,
            hasMore = (offset + limit) < totalCount,
        )
    }

    override suspend fun reorderTask(taskId: String, newPosition: Int) {
        logger.info { "REORDER_TASK | taskId=$taskId | newPosition=$newPosition" }

        val taskIdTyped = com.jervis.common.types.TaskId.fromString(taskId)
        val task = taskRepository.getById(taskIdTyped)
            ?: run {
                logger.warn { "REORDER_TASK_NOT_FOUND | taskId=$taskId" }
                return
            }

        if (task.state != com.jervis.dto.TaskStateEnum.READY_FOR_GPU) {
            logger.warn { "REORDER_TASK_INVALID_STATE | taskId=$taskId | state=${task.state}" }
            return
        }

        taskService.reorderTaskInQueue(task, newPosition)

        // Emit updated queue status
        try {
            emitGlobalQueueStatus()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to emit queue status after reorder" }
        }
    }

    override suspend fun moveTask(taskId: String, targetMode: String) {
        logger.info { "MOVE_TASK | taskId=$taskId | targetMode=$targetMode" }

        val taskIdTyped = com.jervis.common.types.TaskId.fromString(taskId)
        val task = taskRepository.getById(taskIdTyped)
            ?: run {
                logger.warn { "MOVE_TASK_NOT_FOUND | taskId=$taskId" }
                return
            }

        if (task.state != com.jervis.dto.TaskStateEnum.READY_FOR_GPU) {
            logger.warn { "MOVE_TASK_INVALID_STATE | taskId=$taskId | state=${task.state}" }
            return
        }

        val mode = when (targetMode) {
            "FOREGROUND" -> com.jervis.entity.ProcessingMode.FOREGROUND
            "BACKGROUND" -> com.jervis.entity.ProcessingMode.BACKGROUND
            else -> {
                logger.warn { "MOVE_TASK_INVALID_MODE | taskId=$taskId | targetMode=$targetMode" }
                return
            }
        }

        taskService.moveTaskToQueue(task, mode)

        // Emit updated queue status
        try {
            emitGlobalQueueStatus()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to emit queue status after move" }
        }
    }

    override suspend fun cancelOrchestration(taskId: String) {
        val taskIdTyped = com.jervis.common.types.TaskId.fromString(taskId)
        val task = taskRepository.getById(taskIdTyped)
            ?: throw IllegalArgumentException("Task not found: $taskId")
        val threadId = task.orchestratorThreadId
            ?: throw IllegalStateException("Task has no orchestrator thread: $taskId")
        pythonOrchestratorClient.cancelOrchestration(threadId)
        logger.info { "ORCHESTRATION_CANCELLED: taskId=$taskId threadId=$threadId" }
    }

    // --- Task History ---

    override suspend fun getTaskHistory(limit: Int, offset: Int): com.jervis.dto.TaskHistoryPageDto {
        val totalCount = taskHistoryRepository.countAllBy()
        val items = taskHistoryRepository
            .findAllByOrderByCompletedAtDesc(PageRequest.of(offset / limit.coerceAtLeast(1), limit.coerceAtLeast(1)))
            .toList()
            .map { doc ->
                // Prefer orchestratorSteps (has durations) over workflowSteps
                val nodes = doc.orchestratorStepsJson?.let { json ->
                    try {
                        val elements = Json.decodeFromString<List<kotlinx.serialization.json.JsonObject>>(json)
                        elements.map { obj ->
                            com.jervis.dto.TaskHistoryNodeDto(
                                node = obj["node"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "",
                                label = obj["label"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "",
                                durationMs = obj["durationMs"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() },
                            )
                        }
                    } catch (_: Exception) { null }
                } ?: doc.workflowStepsJson?.let { json ->
                    try {
                        Json.decodeFromString<List<com.jervis.dto.ui.ChatMessage.WorkflowStep>>(json)
                            .map { step ->
                                com.jervis.dto.TaskHistoryNodeDto(
                                    node = step.node,
                                    label = step.label,
                                )
                            }
                    } catch (_: Exception) { emptyList() }
                } ?: emptyList()

                com.jervis.dto.TaskHistoryEntryDto(
                    id = doc.id.toString(),
                    taskId = doc.taskId,
                    taskPreview = doc.taskPreview,
                    projectName = doc.projectName,
                    clientName = doc.clientName,
                    startedAt = doc.startedAt?.toString(),
                    completedAt = doc.completedAt.toString(),
                    status = doc.status,
                    processingMode = doc.processingMode,
                    nodes = nodes,
                )
            }

        return com.jervis.dto.TaskHistoryPageDto(
            items = items,
            totalCount = totalCount,
            hasMore = (offset + limit) < totalCount,
        )
    }

    // --- Internal Helpers ---

    /**
     * Convert TaskDocument to PendingTaskItemDto for queue display.
     */
    private suspend fun com.jervis.entity.TaskDocument.toPendingTaskItemDto(): com.jervis.dto.PendingTaskItemDto {
        val projectName = this.projectId?.let { pid ->
            try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
        } ?: "General"

        val preview = this.content.take(60).let {
            if (this.content.length > 60) "$it..." else it
        }

        val taskTypeLabel = when (this.type) {
            com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING -> "Asistent"
            com.jervis.dto.TaskTypeEnum.WIKI_PROCESSING -> "Wiki"
            com.jervis.dto.TaskTypeEnum.BUGTRACKER_PROCESSING -> "BugTracker"
            com.jervis.dto.TaskTypeEnum.EMAIL_PROCESSING -> "Email"
            else -> this.type.toString()
        }

        return com.jervis.dto.PendingTaskItemDto(
            taskId = this.id.toString(),
            projectName = projectName,
            preview = preview,
            taskType = taskTypeLabel,
            processingMode = this.processingMode.name,
            queuePosition = this.queuePosition,
        )
    }

    /**
     * Save base64-encoded attachment to disk and map to domain object.
     * If contentBase64 is present, decode and save; otherwise use existing storagePath.
     */
    private fun saveAndMapAttachment(dto: com.jervis.dto.AttachmentDto): com.jervis.domain.atlassian.AttachmentMetadata {
        val storagePath = if (!dto.contentBase64.isNullOrBlank()) {
            val storageDir = Path.of(attachmentStorageDir)
            Files.createDirectories(storageDir)
            val targetFile = storageDir.resolve("${dto.id}_${dto.filename}")
            val bytes = Base64.getDecoder().decode(dto.contentBase64)
            Files.write(targetFile, bytes)
            logger.info { "ATTACHMENT_SAVED | id=${dto.id} | filename=${dto.filename} | size=${bytes.size} | path=$targetFile" }
            targetFile.toString()
        } else {
            dto.storagePath
        }

        return com.jervis.domain.atlassian.AttachmentMetadata(
            id = dto.id,
            filename = dto.filename,
            mimeType = dto.mimeType,
            sizeBytes = dto.sizeBytes,
            storagePath = storagePath,
            type = com.jervis.domain.atlassian.classifyAttachmentType(dto.mimeType),
        )
    }

    /**
     * Build a global queue status response (all clients, all queues).
     */
    suspend fun buildGlobalQueueStatusResponse(): ChatResponseDto {
        val (runningTask, queueSize) = taskService.getGlobalQueueStatus()

        val foregroundTasks = taskService.getPendingForegroundTasks()
        val backgroundTasks = taskService.getPendingBackgroundTasks()

        val pendingItems = buildMap {
            put("pendingItemCount", foregroundTasks.size.toString())
            foregroundTasks.forEachIndexed { index, task ->
                val preview = task.content.take(60).let {
                    if (task.content.length > 60) "$it..." else it
                }
                val pName = task.projectId?.let { pid ->
                    try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
                } ?: "General"
                put("pendingItem_${index}_preview", preview)
                put("pendingItem_${index}_project", pName)
                put("pendingItem_${index}_taskId", task.id.toString())
            }
            put("backgroundItemCount", backgroundTasks.size.toString())
            backgroundTasks.forEachIndexed { index, task ->
                val preview = task.content.take(60).let {
                    if (task.content.length > 60) "$it..." else it
                }
                val pName = task.projectId?.let { pid ->
                    try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
                } ?: "General"
                put("backgroundItem_${index}_preview", preview)
                put("backgroundItem_${index}_project", pName)
                put("backgroundItem_${index}_taskId", task.id.toString())
            }
        }

        val orchestratorHealthy = pythonOrchestratorClient.circuitBreaker.currentState() !=
            com.jervis.configuration.CircuitBreaker.State.OPEN

        val metadata = if (runningTask != null) {
            val projectName = runningTask.projectId?.let { pid ->
                try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
            } ?: "General"
            val taskPreview = runningTask.content.take(50).let {
                if (runningTask.content.length > 50) "$it..." else it
            }
            val taskTypeLabel = when (runningTask.type) {
                com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING -> "Asistent"
                com.jervis.dto.TaskTypeEnum.WIKI_PROCESSING -> "Wiki"
                com.jervis.dto.TaskTypeEnum.BUGTRACKER_PROCESSING -> "BugTracker"
                com.jervis.dto.TaskTypeEnum.EMAIL_PROCESSING -> "Email"
                else -> runningTask.type.toString()
            }
            mapOf(
                "runningProjectId" to (runningTask.projectId?.toString() ?: "__background__"),
                "runningProjectName" to projectName,
                "runningTaskPreview" to taskPreview,
                "runningTaskType" to taskTypeLabel,
                "queueSize" to queueSize.toString(),
                "orchestratorHealthy" to orchestratorHealthy.toString(),
            )
        } else {
            mapOf(
                "runningProjectId" to "none",
                "runningProjectName" to "None",
                "runningTaskPreview" to "",
                "runningTaskType" to "",
                "queueSize" to queueSize.toString(),
                "orchestratorHealthy" to orchestratorHealthy.toString(),
            )
        }

        return ChatResponseDto(
            message = "Queue status update",
            type = ChatResponseType.QUEUE_STATUS,
            metadata = metadata + pendingItems,
        )
    }

    /**
     * Emit global queue status to all connected clients.
     */
    suspend fun emitGlobalQueueStatus() {
        val response = buildGlobalQueueStatusResponse()
        emitQueueStatusToAll(response)
    }
}
