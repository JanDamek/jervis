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
import com.jervis.repository.ProjectRepository
import com.jervis.service.CloudModelPolicyResolver
import com.jervis.service.IChatService
import com.jervis.service.background.BackgroundEngine
import com.jervis.service.chat.ChatService
import com.jervis.service.chat.ChatStreamEvent
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
    private val projectRepository: ProjectRepository,
    private val cloudModelPolicyResolver: CloudModelPolicyResolver,
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

        // Load recent history first
        try {
            val history = chatService.getHistory(limit = 15)
            for (msg in history.messages) {
                val responseType = when (msg.role) {
                    MessageRole.USER -> ChatResponseType.USER_MESSAGE
                    MessageRole.BACKGROUND -> ChatResponseType.BACKGROUND_RESULT
                    MessageRole.ALERT -> ChatResponseType.URGENT_ALERT
                    else -> ChatResponseType.FINAL
                }
                emit(
                    ChatResponseDto(
                        message = msg.content,
                        type = responseType,
                        metadata = mapOf(
                            "sender" to msg.role.name.lowercase(),
                            "timestamp" to msg.timestamp.toString(),
                            "fromHistory" to "true",
                            "sequence" to msg.sequence.toString(),
                        ),
                    ),
                )
            }

            // Emit persisted scope so UI restores client/project/group selection on startup
            val clientId = history.activeClientId
            if (!clientId.isNullOrBlank()) {
                emit(
                    ChatResponseDto(
                        message = "",
                        type = ChatResponseType.SCOPE_CHANGE,
                        metadata = buildMap {
                            put("clientId", clientId)
                            put("projectId", history.activeProjectId ?: "")
                            put("groupId", history.activeGroupId ?: "")
                        },
                    ),
                )
            }
        } catch (e: CancellationException) {
            logger.debug(e) { "Chat subscription cancelled by client (reconnect)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load chat history for subscription" }
            emit(
                ChatResponseDto(
                    message = "Nepodařilo se načíst historii chatu: ${e.message}",
                    type = ChatResponseType.ERROR,
                    metadata = mapOf("historyLoadFailed" to "true"),
                ),
            )
        }

        // Emit sync marker
        emit(
            ChatResponseDto(
                message = "HISTORY_LOADED",
                type = ChatResponseType.FINAL,
                metadata = mapOf("status" to "synchronized"),
            ),
        )

        // Stream live events
        chatEventStream.collect { event ->
            emit(event)
        }
    }

    override suspend fun sendMessage(
        text: String,
        clientMessageId: String?,
        activeClientId: String?,
        activeProjectId: String?,
        contextTaskId: String?,
    ) {
        logger.info { "CHAT_SEND | text='${text.take(80)}' | clientId=$activeClientId | projectId=$activeProjectId" }

        // Start processing in background — results arrive via chatEventStream
        backgroundScope.launch {
            try {
                // Resolve CloudModelPolicy (project → group → client hierarchy) and groupId
                val project = try {
                    activeProjectId?.let { pid ->
                        projectRepository.getById(ProjectId(ObjectId(pid)))
                    }
                } catch (_: Exception) { null }
                val policy = cloudModelPolicyResolver.resolve(
                    clientId = activeClientId?.let { ClientId(ObjectId(it)) },
                    projectId = activeProjectId?.let { ProjectId(ObjectId(it)) },
                )
                val maxOpenRouterTier = policy.maxOpenRouterTier.name
                val activeGroupId = project?.groupId?.toString()

                // Persistence-first: sendMessage() saves USER_MESSAGE to DB before returning the Flow
                val eventFlow = chatService.sendMessage(
                    text = text,
                    clientMessageId = clientMessageId,
                    activeClientId = activeClientId,
                    activeProjectId = activeProjectId,
                    activeGroupId = activeGroupId,
                    contextTaskId = contextTaskId,
                    maxOpenRouterTier = maxOpenRouterTier,
                )

                // Emit user message AFTER DB save (persistence-first) for reliable reconnect
                chatEventStream.emit(
                    ChatResponseDto(
                        message = text,
                        type = ChatResponseType.USER_MESSAGE,
                        metadata = mapOf(
                            "sender" to "user",
                            "timestamp" to java.time.Instant.now().toString(),
                        ),
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
                            // Resolve groupId from project for cross-device scope restore
                            val newGroupId = newProjectId?.let { pid ->
                                try {
                                    projectRepository.getById(ProjectId(ObjectId(pid)))?.groupId?.toString()
                                } catch (_: Exception) { null }
                            }
                            if (newClientId != null) {
                                chatService.updateSessionScope(clientId = newClientId, projectId = newProjectId, groupId = newGroupId)
                            }
                            chatEventStream.emit(
                                ChatResponseDto(
                                    message = event.content,
                                    type = responseType,
                                    messageId = responseMessageId,
                                    metadata = metadata,
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
                // Always release foreground chat lock so background tasks can resume.
                // Python's finally block in async generator is unreliable for this.
                backgroundEngine.registerForegroundChatEnd()
            }
        }
    }

    override suspend fun getChatHistory(limit: Int, beforeMessageId: String?): ChatHistoryDto {
        val result = chatService.getHistory(limit = limit, beforeMessageId = beforeMessageId)

        val messages = result.messages.map { msg ->
            ChatMessageDto(
                role = when (msg.role) {
                    MessageRole.USER -> ChatRole.USER
                    MessageRole.ASSISTANT -> ChatRole.ASSISTANT
                    MessageRole.SYSTEM -> ChatRole.SYSTEM
                    MessageRole.BACKGROUND -> ChatRole.BACKGROUND
                    MessageRole.ALERT -> ChatRole.ALERT
                },
                content = msg.content,
                timestamp = msg.timestamp.toString(),
                correlationId = msg.correlationId,
                metadata = msg.metadata,
                sequence = msg.sequence,
                messageId = msg.id.toString(),
            )
        }

        return ChatHistoryDto(
            messages = messages,
            hasMore = result.hasMore,
            oldestMessageId = result.oldestMessageId,
            activeClientId = result.activeClientId,
            activeProjectId = result.activeProjectId,
            activeGroupId = result.activeGroupId,
        )
    }

    override suspend fun updateScope(clientId: String?, projectId: String?) {
        if (clientId.isNullOrBlank()) return
        val groupId = projectId?.let { pid ->
            try {
                projectRepository.getById(ProjectId(ObjectId(pid)))?.groupId?.toString()
            } catch (_: Exception) { null }
        }
        chatService.updateSessionScope(clientId = clientId, projectId = projectId, groupId = groupId)
        logger.info { "CHAT_SCOPE_UPDATE | clientId=$clientId | projectId=$projectId | groupId=$groupId" }
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
        metadata: Map<String, String> = emptyMap(),
    ) {
        val session = chatService.getOrCreateActiveSession()
        val content = if (success) {
            "[Background] $taskTitle: $summary"
        } else {
            "[Background FAILED] $taskTitle: $summary"
        }

        // Persist to DB
        chatService.saveSystemMessage(
            sessionId = session.id,
            role = MessageRole.BACKGROUND,
            content = content,
            metadata = metadata,
        )

        // Emit to live stream
        val allMetadata = buildMap {
            put("sender", "background")
            put("taskTitle", taskTitle)
            put("success", success.toString())
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
        summary: String,
        suggestedAction: String? = null,
    ) {
        val session = chatService.getOrCreateActiveSession()
        val content = buildString {
            append("[Urgent Alert] $summary")
            if (suggestedAction != null) {
                append("\nSuggested action: $suggestedAction")
            }
        }

        // Persist to DB
        chatService.saveSystemMessage(
            sessionId = session.id,
            role = MessageRole.ALERT,
            content = content,
            metadata = mapOf("sourceUrn" to sourceUrn),
        )

        // Emit to live stream
        chatEventStream.emit(
            ChatResponseDto(
                message = content,
                type = ChatResponseType.URGENT_ALERT,
                metadata = buildMap {
                    put("sender", "alert")
                    put("sourceUrn", sourceUrn)
                    put("timestamp", java.time.Instant.now().toString())
                    if (suggestedAction != null) put("suggestedAction", suggestedAction)
                },
            ),
        )
        logger.info { "CHAT_PUSH_ALERT | sourceUrn=$sourceUrn | summary=${summary.take(80)}" }
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
