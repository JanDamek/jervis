package com.jervis.chat

import com.jervis.dto.chat.ChatHistoryDto
import com.jervis.dto.chat.ChatMessageDto
import com.jervis.dto.chat.ChatResponseDto
import com.jervis.dto.chat.ChatResponseType
import com.jervis.dto.chat.ChatRole
import com.jervis.dto.task.TaskStateEnum
import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.client.ClientRepository
import com.jervis.project.ProjectRepository
import com.jervis.task.TaskRepository
import com.jervis.infrastructure.llm.CloudModelPolicyResolver
import com.jervis.service.chat.IChatService
import com.jervis.task.BackgroundEngine
import com.jervis.task.TaskGraphExistsService
import org.bson.types.ObjectId
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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
    private val chatMessageService: ChatMessageService,
    private val backgroundEngine: BackgroundEngine,
    private val clientRepository: ClientRepository,
    private val projectRepository: ProjectRepository,
    private val projectGroupRepository: com.jervis.projectgroup.ProjectGroupRepository,
    private val cloudModelPolicyResolver: CloudModelPolicyResolver,
    private val taskRepository: TaskRepository,
    private val taskGraphExistsService: TaskGraphExistsService,
    private val unifiedTimelineService: UnifiedTimelineService,
    private val preferenceService: com.jervis.preferences.PreferenceService,
    private val agentQuestionRepository: com.jervis.agent.PendingAgentQuestionRepository,
    private val agentQuestionService: com.jervis.agent.AgentQuestionService,
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
        attachments: List<com.jervis.dto.chat.AttachmentDto>,
        tierOverride: String?,
        clientTimezone: String?,
    ) {
        // Validate ObjectId format — reject placeholder values like "client_123"
        val safeClientId = activeClientId?.takeIf { ObjectId.isValid(it) }
        val safeProjectId = activeProjectId?.takeIf { ObjectId.isValid(it) }
        val safeGroupId = activeGroupId?.takeIf { ObjectId.isValid(it) }
        logger.info { "CHAT_SEND | text='${text.take(80)}' | clientId=$safeClientId | projectId=$safeProjectId | groupId=$safeGroupId | tz=$clientTimezone" }

        // Persist client timezone as last-known for scheduler/calendar (fire-and-forget)
        val resolvedTimezone = clientTimezone ?: "Europe/Prague"

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
                // UI tier override takes precedence over policy (temporary per-chat switch)
                val maxOpenRouterTier = if (!tierOverride.isNullOrBlank() &&
                    tierOverride in listOf("NONE", "FREE", "PAID", "PREMIUM")
                ) {
                    logger.info { "CHAT_TIER_OVERRIDE | policy=${policy.maxOpenRouterTier.name} → override=$tierOverride" }
                    tierOverride
                } else {
                    policy.maxOpenRouterTier.name
                }
                // Use explicitly provided groupId, or derive from project
                val resolvedGroupId = safeGroupId ?: project?.groupId?.toString()

                // Resolve group name for memory graph hierarchy
                val groupName = resolvedGroupId?.let { gid ->
                    try {
                        val groupRepo = projectGroupRepository
                        groupRepo.getById(com.jervis.common.types.ProjectGroupId(ObjectId(gid)))?.name
                    } catch (_: Exception) { null }
                }

                // Update last-known timezone for scheduler/calendar (non-blocking)
                try {
                    preferenceService.setPreference(
                        key = com.jervis.preferences.PreferenceService.KEY_TIMEZONE,
                        value = resolvedTimezone,
                        source = com.jervis.preferences.PreferenceSource.USER_IMPLICIT,
                        confidence = 1.0,
                        description = "Auto-updated from client device timezone",
                    )
                } catch (e: Exception) {
                    logger.warn { "Failed to persist client timezone: ${e.message}" }
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
                    clientTimezone = resolvedTimezone,
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

    override suspend fun getChatHistory(
        limit: Int, beforeMessageId: String?, showChat: Boolean, showTasks: Boolean, showNeedReaction: Boolean,
        filterClientId: String?, filterProjectId: String?, filterGroupId: String?,
    ): ChatHistoryDto {
        logger.info { "CHAT_HISTORY | filterClient=$filterClientId filterProject=$filterProjectId filterGroup=$filterGroupId limit=$limit" }
        // "K reakci" badge = tasks in state USER_TASK (regardless of type)
        // + actionable BACKGROUNDs + pending agent questions.
        // ERROR tasks belong to retry/escalation, NOT to K reakci.
        val pendingUserTasks = taskRepository.countByState(TaskStateEnum.USER_TASK).toInt()
        val actionableBackground = chatService.countActionableBackground().toInt()
        val pendingQuestions = (agentQuestionRepository.countByState(com.jervis.agent.QuestionState.PENDING) +
            agentQuestionRepository.countByState(com.jervis.agent.QuestionState.PRESENTED)).toInt()
        val userTaskCount = pendingUserTasks + actionableBackground + pendingQuestions
        val session = chatService.getOrCreateActiveSession()

        // ── Load all K reakci items from DB (global, no scope filter) ──
        // Skip on pagination (beforeMessageId set) — K reakci items are always in initial load.
        val kreakciDtos = if (showNeedReaction && beforeMessageId == null) {
            val items = mutableListOf<ChatMessageDto>()

            // 1. Tasks waiting for the user (state=USER_TASK, type-agnostic).
            //    ERROR tasks belong to retry/escalation, NOT to K reakci.
            try {
                val pendingTasks = taskRepository.findByStateOrderByCreatedAtAsc(TaskStateEnum.USER_TASK).toList()
                items.addAll(pendingTasks.map { task ->
                    ChatMessageDto(
                        role = ChatRole.ALERT,
                        content = "[K reakci] ${task.taskName ?: "Úloha vyžaduje pozornost"}${
                            task.pendingUserQuestion?.let { "\n\n$it" } ?: ""
                        }",
                        timestamp = task.createdAt?.toString() ?: "",
                        messageId = task.id.toString(),
                        metadata = buildMap {
                            put("needsReaction", "true")
                            put("taskId", task.id.toString())
                            put("taskType", task.type.name)
                            put("sourceLabel", task.sourceUrn.uiLabel())
                            put("success", "true")
                            task.clientId.let { put("clientId", it.toString()) }
                            task.projectId?.let { put("projectId", it.toString()) }
                        },
                    )
                })
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load pending user tasks for K reakci" }
            }

            // 2. Pending agent questions
            try {
                val activeStates = listOf(com.jervis.agent.QuestionState.PENDING, com.jervis.agent.QuestionState.PRESENTED)
                val questions = agentQuestionRepository.findByStateIn(activeStates).toList()
                items.addAll(questions.map { q ->
                    ChatMessageDto(
                        role = ChatRole.ALERT,
                        content = "[K reakci] ${q.question}",
                        timestamp = q.createdAt.toString(),
                        messageId = q.id.toString(),
                        metadata = buildMap {
                            put("needsReaction", "true")
                            put("taskId", q.taskId)
                            put("taskType", "AGENT_QUESTION")
                            put("questionId", q.id.toString())
                            put("success", "true")
                            q.clientId?.let { put("clientId", it) }
                            q.projectId?.let { put("projectId", it) }
                        },
                    )
                })
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load agent questions for K reakci" }
            }

            items
        } else emptyList()

        // ── Scope-aware path: use ReactiveMongoTemplate with dynamic Criteria ──
        if (filterClientId != null) {
            val groupProjectIds = if (filterGroupId != null) {
                projectRepository.findByGroupIdAndActiveTrue(com.jervis.common.types.ProjectGroupId.fromString(filterGroupId))
                    .toList()
                    .map { it.id.toString() }
            } else null

            val beforeId = beforeMessageId?.let { org.bson.types.ObjectId(it) }
            val messages = chatMessageService.getMessagesWithScope(
                conversationId = session.id,
                limit = limit,
                beforeId = beforeId,
                filterClientId = filterClientId,
                filterProjectId = filterProjectId,
                groupProjectIds = groupProjectIds,
                showChat = showChat,
                showTasks = showTasks,
                showNeedReaction = showNeedReaction,
            )
            val dtos = messages.map { msg ->
                val outOfScope = filterClientId != null && msg.clientId != null && msg.clientId != filterClientId
                msg.toChatMessageDto(taskGraphExistsService, isOutOfScope = outOfScope)
            }
            // Merge USER_TASKs (global) with scoped chat messages, chronological order.
            // Deduplicate: USER_TASKs from tasks collection may overlap with ALERT messages
            // already in chat_messages (same taskId). Keep the chat_messages version (has sequence).
            val existingTaskIds = dtos.mapNotNull { it.metadata["taskId"] }.toSet()
            val dedupedUserTasks = kreakciDtos.filter { it.metadata["taskId"] !in existingTaskIds }
            // Merge: USER_TASKs always included (few items), pagination cursor only for chat_messages.
            val merged = (dtos + dedupedUserTasks).sortedBy { it.timestamp }
            return ChatHistoryDto(
                messages = merged,
                hasMore = messages.size >= limit,
                oldestMessageId = messages.firstOrNull()?.timestamp?.toString(),
                userTaskCount = userTaskCount,
                backgroundMessageCount = 0,
            )
        }

        // ── No filterClientId — return only USER_TASKs if K reakci is active ──
        if (kreakciDtos.isNotEmpty()) {
            return ChatHistoryDto(
                messages = kreakciDtos.sortedBy { it.timestamp },
                hasMore = false,
                oldestMessageId = null,
                userTaskCount = userTaskCount,
                backgroundMessageCount = 0,
            )
        }

        // ── No filterClientId and no USER_TASKs = empty result ──
        logger.warn { "CHAT_HISTORY_NO_SCOPE | No filterClientId — returning empty. showChat=$showChat showTasks=$showTasks showReaction=$showNeedReaction" }
        return ChatHistoryDto(
            messages = emptyList(),
            hasMore = false,
            oldestMessageId = null,
            userTaskCount = userTaskCount,
            backgroundMessageCount = 0,
        )
    }

    /** Load chat history via Spring Data (no aggregation). */
    private suspend fun loadChatHistory(
        session: ChatSessionDocument,
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
        session: ChatSessionDocument,
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

    override suspend fun getTaskConversationHistory(taskId: String, limit: Int): ChatHistoryDto {
        // Phase 5 chat-as-primary: load message history scoped to a single
        // task's conversation. The conversationId stored on each
        // ChatMessageDocument equals task.id.value, so we can fetch directly.
        val taskObjectId = try {
            ObjectId(taskId)
        } catch (e: Exception) {
            logger.warn { "TASK_CONV_HISTORY: invalid taskId=$taskId — ${e.message}" }
            return ChatHistoryDto(
                messages = emptyList(),
                hasMore = false,
                oldestMessageId = null,
                userTaskCount = 0,
                backgroundMessageCount = 0,
            )
        }
        val docs = chatMessageService.getAllMessages(taskObjectId)
            .takeLast(limit)
        val messages = docs.map { it.toChatMessageDto(taskGraphExistsService) }
        return ChatHistoryDto(
            messages = messages,
            hasMore = false,
            oldestMessageId = docs.firstOrNull()?.id?.toString(),
            userTaskCount = 0,
            backgroundMessageCount = 0,
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

    override suspend fun dismissAllActionable(): Int {
        var count = 0

        // 1. Dismiss tasks waiting for the user (state=USER_TASK → DONE).
        //    State alone is the discriminator — type-agnostic. ERROR tasks
        //    belong to the retry/escalation path and are NOT dismissed here.
        val pendingTasks = taskRepository.findByStateOrderByCreatedAtAsc(TaskStateEnum.USER_TASK).toList()
        for (task in pendingTasks) {
            taskRepository.save(task.copy(state = TaskStateEnum.DONE))
            count++
        }

        // 2. Dismiss actionable BACKGROUND messages in chat_messages
        val bgCount = chatService.dismissAllActionableBackground()
        count += bgCount

        // 3. Expire all pending agent questions
        val expiredQuestions = agentQuestionService.expireOldQuestions()
        // Also expire non-old questions (dismiss = expire all)
        val activeStates = listOf(com.jervis.agent.QuestionState.PENDING, com.jervis.agent.QuestionState.PRESENTED)
        val remaining = agentQuestionRepository.findByStateIn(activeStates).toList()
        for (q in remaining) {
            agentQuestionRepository.save(q.copy(state = com.jervis.agent.QuestionState.EXPIRED))
            count++
        }

        logger.info { "DISMISS_ALL_ACTIONABLE | userTasks=${pendingTasks.size} background=$bgCount expiredQuestions=${expiredQuestions + remaining.size} total=$count" }
        return count
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
        clientId: String? = null,
        projectId: String? = null,
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
            clientId = clientId,
            projectId = projectId,
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
     * Push a thinking graph update from background orchestrator into the chat stream.
     * "started"/"completed"/"failed" are persisted to DB; intermediate updates are live-only.
     */
    suspend fun pushThinkingGraphUpdate(
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
            put("sender", "thinking_graph")
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
                type = ChatResponseType.THINKING_GRAPH_UPDATE,
                metadata = allMetadata,
            ),
        )
        logger.info { "CHAT_PUSH_THINKING_GRAPH | taskId=$taskId | status=$status | title=$taskTitle" }
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
            "thinking_graph_update" -> ChatResponseType.THINKING_GRAPH_UPDATE
            "thought_context" -> ChatResponseType.THOUGHT_CONTEXT
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
    override fun streamVoiceChat(
        audioChunks: kotlinx.coroutines.flow.Flow<com.jervis.dto.chat.VoiceAudioChunk>,
    ): kotlinx.coroutines.flow.Flow<com.jervis.dto.chat.VoiceChatEvent> {
        return kotlinx.coroutines.flow.flow {
            emit(com.jervis.dto.chat.VoiceChatEvent(type = com.jervis.dto.chat.VoiceChatEventType.ERROR, text = "Voice chat not yet implemented via kRPC"))
        }
    }
}

/** Convert ChatMessageDocument to ChatMessageDto with graph enrichment. */
private suspend fun ChatMessageDocument.toChatMessageDto(
    taskGraphExistsService: com.jervis.task.TaskGraphExistsService,
    isOutOfScope: Boolean = false,
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
        isDecomposed = isDecomposed,
        parentRequestId = parentRequestId,
        isOutOfScope = isOutOfScope,
    )
}
