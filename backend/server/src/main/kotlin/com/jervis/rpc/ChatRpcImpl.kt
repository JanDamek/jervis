package com.jervis.rpc

import com.jervis.dto.ChatHistoryDto
import com.jervis.dto.ChatMessageDto
import com.jervis.dto.ChatResponseDto
import com.jervis.dto.ChatResponseType
import com.jervis.dto.ChatRole
import com.jervis.entity.MessageRole
import com.jervis.common.types.ClientId
import com.jervis.entity.OpenRouterTier
import com.jervis.common.types.ProjectId
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.repository.ClientRepository
import com.jervis.repository.ProjectRepository
import com.jervis.repository.TaskRepository
import com.jervis.service.CloudModelPolicyResolver
import com.jervis.service.IChatService
import com.jervis.service.background.BackgroundEngine
import com.jervis.service.chat.ChatService
import com.jervis.service.chat.ChatStreamEvent
import com.jervis.service.chat.UnifiedTimelineService
import com.jervis.service.graph.TaskGraphExistsService
import org.bson.types.ObjectId
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * ChatRpcImpl — kRPC implementation for foreground chat.
 *
 * Bridges UI ↔ ChatService ↔ Python /chat.
 * Real-time events from Python are forwarded to UI via SharedFlow.
 */
@Component
class ChatRpcImpl(
    private val chatService: ChatService,
    private val backgroundEngine: BackgroundEngine,
    private val clientRepository: ClientRepository,
    private val projectRepository: ProjectRepository,
    private val projectGroupRepository: com.jervis.repository.ProjectGroupRepository,
    private val cloudModelPolicyResolver: CloudModelPolicyResolver,
    private val taskRepository: TaskRepository,
    private val taskGraphExistsService: TaskGraphExistsService,
    private val unifiedTimelineService: UnifiedTimelineService,
) : IChatService {
    private val logger = KotlinLogging.logger {}
    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val chatEventStream = MutableSharedFlow<ChatResponseDto>(
        replay = 10,
        extraBufferCapacity = 200,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun subscribeToChatEvents(): Flow<ChatResponseDto> = flow {
        logger.info { "CHAT_SUBSCRIBE: Client connected to chat events" }

        // No history emission — client loads history via getChatHistory(filterMode=...) independently.
        // Only emit scope restore + sync marker, then stream live events.
        try {
            // Emit persisted scope so UI restores client/project/group selection on startup
            val session = chatService.getOrCreateActiveSession()
            val clientId = session.lastClientId?.takeIf { ObjectId.isValid(it) }
            if (!clientId.isNullOrBlank()) {
                emit(
                    ChatResponseDto(
                        message = "",
                        type = ChatResponseType.SCOPE_CHANGE,
                        metadata = buildMap {
                            put("clientId", clientId)
                            put("projectId", session.lastProjectId ?: "")
                            put("groupId", session.lastGroupId ?: "")
                        },
                    ),
                )
            }
        } catch (e: CancellationException) {
            logger.debug(e) { "Chat subscription cancelled by client (reconnect)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load session for scope restore: ${e.message}" }
        }

        // Emit sync marker — client knows subscription is ready
        emit(
            ChatResponseDto(
                message = "HISTORY_LOADED",
                type = ChatResponseType.FINAL,
                metadata = mapOf("status" to "synchronized"),
            ),
        )

        // Stream live events only
        chatEventStream.collect { event ->
            emit(event)
        }
    }

    override suspend fun sendMessage(
        text: String,
        clientMessageId: String?,
        activeClientId: String?,
        activeProjectId: String?,
        activeGroupId: String?,
        contextTaskId: String?,
        attachments: List<com.jervis.dto.AttachmentDto>,
    ) {
        // Validate ObjectId format — reject placeholder values like "client_123"
        val safeClientId = activeClientId?.takeIf { ObjectId.isValid(it) }
        val safeProjectId = activeProjectId?.takeIf { ObjectId.isValid(it) }
        val safeGroupId = activeGroupId?.takeIf { ObjectId.isValid(it) }
        logger.info { "CHAT_SEND | text='${text.take(80)}' | clientId=$safeClientId | projectId=$safeProjectId | groupId=$safeGroupId" }

        // Start processing in background — results arrive via chatEventStream
        backgroundScope.launch {
            try {
                // Resolve CloudModelPolicy (project → group → client hierarchy) and groupId
                val client = try {
                    safeClientId?.let { cid ->
                        clientRepository.getById(ClientId(ObjectId(cid)))
                    }
                } catch (_: Exception) { null }
                val project = try {
                    safeProjectId?.let { pid ->
                        projectRepository.getById(ProjectId(ObjectId(pid)))
                    }
                } catch (_: Exception) { null }
                val policy = cloudModelPolicyResolver.resolve(
                    clientId = safeClientId?.let { ClientId(ObjectId(it)) },
                    projectId = safeProjectId?.let { ProjectId(ObjectId(it)) },
                )
                val maxOpenRouterTier = policy.maxOpenRouterTier.name
                // Use explicitly provided groupId, or derive from project
                val resolvedGroupId = safeGroupId ?: project?.groupId?.toString()

                // Resolve group name for memory map hierarchy
                val groupName = resolvedGroupId?.let { gid ->
                    try {
                        val groupRepo = projectGroupRepository
                        groupRepo.getById(com.jervis.common.types.ProjectGroupId(ObjectId(gid)))?.name
                    } catch (_: Exception) { null }
                }

                // Persistence-first: sendMessage() saves USER_MESSAGE to DB before returning the Flow
                val eventFlow = chatService.sendMessage(
                    text = text,
                    clientMessageId = clientMessageId,
                    activeClientId = safeClientId,
                    activeProjectId = safeProjectId,
                    activeGroupId = resolvedGroupId,
                    activeClientName = client?.name,
                    activeProjectName = project?.name,
                    activeGroupName = groupName,
                    contextTaskId = contextTaskId,
                    maxOpenRouterTier = maxOpenRouterTier,
                    attachments = attachments,
                )

                // Emit user message AFTER DB save (persistence-first) for reliable reconnect
                chatEventStream.emit(
                    ChatResponseDto(
                        message = text,
                        type = ChatResponseType.USER_MESSAGE,
                        metadata = buildMap {
                            put("sender", "user")
                            put("timestamp", java.time.Instant.now().toString())
                            if (!contextTaskId.isNullOrBlank()) put("contextTaskId", contextTaskId)
                            if (attachments.isNotEmpty()) {
                                put("attachments", attachments.joinToString(", ") { it.filename })
                            }
                        },
                    ),
                )

                // Generate unique messageId for this response — used by UI to accumulate tokens
                val responseMessageId = java.util.UUID.randomUUID().toString()
                val tokenBuffer = StringBuilder()

                eventFlow.collect { event ->
                    val (responseType, metadata) = mapStreamEventToResponse(event)

                    when (event.type) {
                        "scope_change" -> {
                            // Persist scope to session for UI restore on restart
                            val newClientId = event.metadata["clientId"]?.toString()
                            val newProjectId = event.metadata["projectId"]?.toString()?.takeIf { it.isNotBlank() }
                            // Resolve groupId from project — if project belongs to a group,
                            // switch to group scope (groupId set, projectId cleared)
                            val newGroupId = newProjectId?.let { pid ->
                                try {
                                    projectRepository.getById(ProjectId(ObjectId(pid)))?.groupId?.toString()
                                } catch (_: Exception) { null }
                            }
                            // When project belongs to a group, use group as the scope
                            val effectiveProjectId = if (newGroupId != null) null else newProjectId
                            if (newClientId != null) {
                                chatService.updateSessionScope(clientId = newClientId, projectId = effectiveProjectId, groupId = newGroupId)
                            }
                            // Add groupId to metadata so UI can switch to group view
                            val enrichedMetadata = metadata.toMutableMap()
                            if (newGroupId != null) {
                                enrichedMetadata["groupId"] = newGroupId
                                enrichedMetadata.remove("projectId")
                            }
                            chatEventStream.emit(
                                ChatResponseDto(
                                    message = event.content,
                                    type = responseType,
                                    messageId = responseMessageId,
                                    metadata = enrichedMetadata,
                                ),
                            )
                        }
                        "token" -> {
                            tokenBuffer.append(event.content)
                            chatEventStream.emit(
                                ChatResponseDto(
                                    message = event.content,
                                    type = responseType,
                                    messageId = responseMessageId,
                                    metadata = metadata,
                                ),
                            )
                        }
                        "done" -> {
                            // FINAL uses accumulated text (done event has empty content)
                            val finalText = if (tokenBuffer.isNotEmpty()) tokenBuffer.toString() else event.content
                            chatEventStream.emit(
                                ChatResponseDto(
                                    message = finalText,
                                    type = responseType,
                                    messageId = responseMessageId,
                                    metadata = metadata,
                                ),
                            )
                        }
                        "approval_request" -> {
                            chatEventStream.emit(
                                ChatResponseDto(
                                    message = event.content,
                                    type = ChatResponseType.APPROVAL_REQUEST,
                                    messageId = responseMessageId,
                                    metadata = metadata,
                                ),
                            )
                        }
                        // tool_call/tool_result — raw data, not useful for UI progress.
                        // Only "thinking" events provide user-friendly Czech descriptions.
                        "tool_call", "tool_result" -> {
                            // Skip — don't emit raw tool data to chat stream
                        }
                        else -> {
                            chatEventStream.emit(
                                ChatResponseDto(
                                    message = event.content,
                                    type = responseType,
                                    messageId = responseMessageId,
                                    metadata = metadata,
                                ),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "CHAT_PROCESSING_ERROR | text='${text.take(50)}'" }
                chatEventStream.emit(
                    ChatResponseDto(
                        message = "Chyba při zpracování: ${e.message}",
                        type = ChatResponseType.ERROR,
                    ),
                )
            } finally {
                // Always release GPU reservation so background tasks can resume.
                // Python's finally block in async generator is unreliable for this.
                backgroundEngine.releaseGpuForChat()
            }
        }
    }

    override suspend fun getChatHistory(limit: Int, beforeMessageId: String?, showChat: Boolean, showTasks: Boolean, showNeedReaction: Boolean): ChatHistoryDto {
        val userTaskCount = taskRepository.countByTypeAndState(TaskTypeEnum.USER_TASK, TaskStateEnum.USER_TASK).toInt()
        val session = chatService.getOrCreateActiveSession()

        // Optimized paths for single-filter cases (no aggregation needed)
        if (showChat && !showTasks && !showNeedReaction) {
            // Chat only — Spring Data derived query (most efficient)
            return loadChatHistory(session, limit, beforeMessageId, excludeBackground = true, userTaskCount = userTaskCount)
        }
        if (!showChat && showTasks && !showNeedReaction) {
            // Tasks only — Spring Data BACKGROUND-only query
            return loadBackgroundHistory(session, limit, beforeMessageId, userTaskCount)
        }
        if (showChat && showTasks && !showNeedReaction) {
            // Chat + Tasks — all messages, no filter
            return loadChatHistory(session, limit, beforeMessageId, excludeBackground = false, userTaskCount = userTaskCount)
        }

        // Any combination involving K reakci → aggregation pipeline (handles $unionWith for USER_TASK)
        val (messages, hasMore) = unifiedTimelineService.loadTimeline(
            conversationId = session.id,
            limit = limit,
            beforeTimestamp = beforeMessageId,
            includeChat = showChat,
            includeTasks = showTasks,
            includeNeedReaction = showNeedReaction,
        )
        val allTaskIds = messages.mapNotNull { it.metadata["taskId"] ?: it.metadata["contextTaskId"] }.toSet()
        val taskIdsWithGraph = taskGraphExistsService.findExistingGraphTaskIds(allTaskIds)
        val enriched = messages.map { msg ->
            val tid = msg.metadata["taskId"] ?: msg.metadata["contextTaskId"]
            if (tid != null) msg.copy(metadata = msg.metadata + ("hasGraph" to (tid in taskIdsWithGraph).toString()))
            else msg
        }
        return ChatHistoryDto(
            messages = enriched,
            hasMore = hasMore,
            oldestMessageId = messages.firstOrNull()?.timestamp,
            userTaskCount = userTaskCount,
            backgroundMessageCount = 0,
        )
    }

    /** Load chat history via Spring Data (no aggregation). */
    private suspend fun loadChatHistory(
        session: com.jervis.entity.ChatSessionDocument,
        limit: Int,
        beforeMessageId: String?,
        excludeBackground: Boolean,
        userTaskCount: Int,
    ): ChatHistoryDto {
        val result = chatService.getHistory(limit = limit, beforeMessageId = beforeMessageId, excludeBackground = excludeBackground)
        val messages = result.messages.map { msg -> msg.toChatMessageDto(taskGraphExistsService) }
        return ChatHistoryDto(
            messages = messages,
            hasMore = result.hasMore,
            oldestMessageId = result.oldestMessageId,
            activeClientId = result.activeClientId,
            activeProjectId = result.activeProjectId,
            activeGroupId = result.activeGroupId,
            userTaskCount = userTaskCount,
            backgroundMessageCount = result.backgroundMessageCount,
        )
    }

    /** Load BACKGROUND-only history via Spring Data. */
    private suspend fun loadBackgroundHistory(
        session: com.jervis.entity.ChatSessionDocument,
        limit: Int,
        beforeMessageId: String?,
        userTaskCount: Int,
    ): ChatHistoryDto {
        val result = chatService.getBackgroundHistory(limit = limit, beforeMessageId = beforeMessageId)
        val messages = result.messages.map { msg -> msg.toChatMessageDto(taskGraphExistsService) }
        return ChatHistoryDto(
            messages = messages,
            hasMore = result.hasMore,
            oldestMessageId = result.oldestMessageId,
            userTaskCount = userTaskCount,
            backgroundMessageCount = result.backgroundMessageCount,
        )
    }

    override suspend fun updateScope(clientId: String?, projectId: String?, groupId: String?) {
        if (clientId.isNullOrBlank()) return
        // Validate ObjectId format — reject placeholder values
        if (!ObjectId.isValid(clientId)) {
            logger.warn { "CHAT_SCOPE_UPDATE_REJECTED | invalid clientId=$clientId" }
            return
        }
        val safeProjectId = projectId?.takeIf { ObjectId.isValid(it) }
        val safeGroupId = groupId?.takeIf { ObjectId.isValid(it) }
        // If groupId is explicitly provided (group selection), use it directly.
        // Otherwise derive from project's groupId (project selection).
        val resolvedGroupId = safeGroupId ?: safeProjectId?.let { pid ->
            try {
                projectRepository.getById(ProjectId(ObjectId(pid)))?.groupId?.toString()
            } catch (_: Exception) { null }
        }
        chatService.updateSessionScope(clientId = clientId, projectId = safeProjectId, groupId = resolvedGroupId)
        logger.info { "CHAT_SCOPE_UPDATE | clientId=$clientId | projectId=$safeProjectId | groupId=$resolvedGroupId" }
    }

    override suspend fun archiveSession() {
        chatService.archiveCurrentSession()
        logger.info { "CHAT_SESSION_ARCHIVED" }
    }

    override suspend fun approveChatAction(approved: Boolean, always: Boolean, action: String?) {
        logger.info { "CHAT_APPROVE | approved=$approved | always=$always | action=$action" }
        chatService.approveChatAction(approved = approved, always = always, action = action)
    }

    // ── Push methods for background results and urgent alerts ────────────

    /**
     * Whether there is at least one active subscriber to the chat event stream.
     * Used to decide whether to push background results / alerts to chat.
     */
    fun isUserOnline(): Boolean = chatEventStream.subscriptionCount.value > 0

    /**
     * Push a background task result into the chat stream.
     * Persists the message to DB and emits to live subscribers.
     */
    suspend fun pushBackgroundResult(
        taskTitle: String,
        summary: String,
        success: Boolean,
        taskId: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) {
        val session = chatService.getOrCreateActiveSession()
        val content = if (success) {
            "[Background] $taskTitle: $summary"
        } else {
            "[Background FAILED] $taskTitle: $summary"
        }

        // Check if task has a graph — single existence check
        val hasGraph = if (taskId != null) {
            taskGraphExistsService.findExistingGraphTaskIds(listOf(taskId)).isNotEmpty()
        } else false

        // Persist to DB — same metadata as live stream so history looks identical
        val persistMetadata = buildMap {
            put("sender", "background")
            put("taskTitle", taskTitle)
            put("success", success.toString())
            if (taskId != null) {
                put("taskId", taskId)
                put("hasGraph", hasGraph.toString())
            }
            put("timestamp", java.time.Instant.now().toString())
            putAll(metadata)
        }
        chatService.saveSystemMessage(
            sessionId = session.id,
            role = MessageRole.BACKGROUND,
            content = content,
            metadata = persistMetadata,
        )

        // Emit to live stream
        val allMetadata = buildMap {
            put("sender", "background")
            put("taskTitle", taskTitle)
            put("success", success.toString())
            if (taskId != null) {
                put("taskId", taskId)
                put("hasGraph", hasGraph.toString())
            }
            put("timestamp", java.time.Instant.now().toString())
            putAll(metadata)
        }
        chatEventStream.emit(
            ChatResponseDto(
                message = content,
                type = ChatResponseType.BACKGROUND_RESULT,
                metadata = allMetadata,
            ),
        )
        logger.info { "CHAT_PUSH_BACKGROUND | title=$taskTitle | success=$success" }
    }

    /**
     * Push an urgent alert into the chat stream.
     * Persists the message to DB and emits to live subscribers.
     */
    suspend fun pushUrgentAlert(
        sourceUrn: String,
        taskId: String? = null,
        taskName: String? = null,
        summary: String,
        suggestedAction: String? = null,
        taskContent: String? = null,
    ) {
        val session = chatService.getOrCreateActiveSession()
        val content = buildString {
            if (!taskName.isNullOrBlank()) {
                append("**$taskName**\n\n")
            }
            append(summary)
        }

        val metadata = buildMap {
            put("sourceUrn", sourceUrn)
            if (taskId != null) put("taskId", taskId)
            if (taskName != null) put("taskName", taskName)
            if (taskContent != null) put("taskContent", taskContent.take(4000))
            if (suggestedAction != null) put("suggestedAction", suggestedAction)
        }

        // Persist to DB
        chatService.saveSystemMessage(
            sessionId = session.id,
            role = MessageRole.ALERT,
            content = content,
            metadata = metadata,
        )

        // Emit to live stream
        chatEventStream.emit(
            ChatResponseDto(
                message = content,
                type = ChatResponseType.URGENT_ALERT,
                metadata = metadata + mapOf(
                    "sender" to "alert",
                    "timestamp" to java.time.Instant.now().toString(),
                ),
            ),
        )
        logger.info { "CHAT_PUSH_ALERT | sourceUrn=$sourceUrn | taskId=$taskId | summary=${summary.take(80)}" }
    }

    /**
     * Push a thinking map update from background orchestrator into the chat stream.
     * "started"/"completed"/"failed" are persisted to DB; intermediate updates are live-only.
     */
    suspend fun pushThinkingMapUpdate(
        taskId: String,
        taskTitle: String,
        graphId: String,
        status: String,
        message: String,
        metadata: Map<String, String> = emptyMap(),
    ) {
        val content = when (status) {
            "started" -> "🧠 Přemýšlím: $taskTitle"
            "completed" -> "✅ Hotovo: $taskTitle"
            "failed" -> "❌ Selhalo: $taskTitle${if (message.isNotBlank()) " — $message" else ""}"
            else -> "🧠 $taskTitle: $message"
        }

        // Check if task has a graph
        val hasGraph = taskGraphExistsService.findExistingGraphTaskIds(listOf(taskId)).isNotEmpty()

        val allMetadata = buildMap {
            put("sender", "thinking_map")
            put("taskId", taskId)
            put("taskTitle", taskTitle)
            put("status", status)
            put("hasGraph", hasGraph.toString())
            if (graphId.isNotBlank()) put("graph_id", graphId)
            put("timestamp", java.time.Instant.now().toString())
            putAll(metadata)
        }

        // Persist terminal states to DB for chat history
        if (status in listOf("started", "completed", "failed")) {
            val session = chatService.getOrCreateActiveSession()
            chatService.saveSystemMessage(
                sessionId = session.id,
                role = MessageRole.BACKGROUND,
                content = content,
                metadata = allMetadata,
            )
        }

        // Emit to live stream
        chatEventStream.emit(
            ChatResponseDto(
                message = content,
                type = ChatResponseType.THINKING_MAP_UPDATE,
                metadata = allMetadata,
            ),
        )
        logger.info { "CHAT_PUSH_THINKING_MAP | taskId=$taskId | status=$status | title=$taskTitle" }
    }

    private fun mapStreamEventToResponse(event: ChatStreamEvent): Pair<ChatResponseType, Map<String, String>> {
        val type = when (event.type) {
            "token" -> ChatResponseType.STREAMING_TOKEN
            "tool_call" -> ChatResponseType.EXECUTING
            "tool_result" -> ChatResponseType.EXECUTING
            "done" -> ChatResponseType.FINAL
            "error" -> ChatResponseType.ERROR
            "thinking" -> ChatResponseType.PLANNING
            "scope_change" -> ChatResponseType.SCOPE_CHANGE
            "thinking_map_update" -> ChatResponseType.THINKING_MAP_UPDATE
            else -> ChatResponseType.EXECUTING
        }

        val metadata = mutableMapOf("eventType" to event.type)
        event.metadata.forEach { (key, value) ->
            if (value != null) {
                metadata[key] = value.toString()
            }
        }

        return type to metadata
    }
}

/** Convert ChatMessageDocument to ChatMessageDto with graph enrichment. */
private suspend fun com.jervis.entity.ChatMessageDocument.toChatMessageDto(
    taskGraphExistsService: com.jervis.service.graph.TaskGraphExistsService,
): ChatMessageDto {
    val msgTaskId = metadata["taskId"] ?: metadata["contextTaskId"]
    val hasGraph = if (msgTaskId != null) {
        taskGraphExistsService.findExistingGraphTaskIds(listOf(msgTaskId)).isNotEmpty()
    } else false
    return ChatMessageDto(
        role = when (role) {
            MessageRole.USER -> ChatRole.USER
            MessageRole.ASSISTANT -> ChatRole.ASSISTANT
            MessageRole.SYSTEM -> ChatRole.SYSTEM
            MessageRole.BACKGROUND -> ChatRole.BACKGROUND
            MessageRole.ALERT -> ChatRole.ALERT
        },
        content = content,
        timestamp = timestamp.toString(),
        correlationId = correlationId,
        metadata = if (msgTaskId != null) metadata + ("hasGraph" to hasGraph.toString()) else metadata,
        sequence = sequence,
        messageId = id.toString(),
    )
}
