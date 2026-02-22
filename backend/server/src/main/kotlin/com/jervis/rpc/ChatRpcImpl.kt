package com.jervis.rpc

import com.jervis.dto.ChatHistoryDto
import com.jervis.dto.ChatMessageDto
import com.jervis.dto.ChatResponseDto
import com.jervis.dto.ChatResponseType
import com.jervis.dto.ChatRole
import com.jervis.entity.MessageRole
import com.jervis.service.IChatService
import com.jervis.service.chat.ChatService
import com.jervis.service.chat.ChatStreamEvent
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
) : IChatService {
    private val logger = KotlinLogging.logger {}
    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val chatEventStream = MutableSharedFlow<ChatResponseDto>(
        replay = 0,
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
        } catch (e: Exception) {
            logger.error(e) { "Failed to load chat history for subscription" }
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

        // Emit user message to stream for immediate UI display
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

        // Start processing in background — results arrive via chatEventStream
        backgroundScope.launch {
            try {
                val eventFlow = chatService.sendMessage(
                    text = text,
                    clientMessageId = clientMessageId,
                    activeClientId = activeClientId,
                    activeProjectId = activeProjectId,
                    contextTaskId = contextTaskId,
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
                            if (newClientId != null) {
                                chatService.updateSessionScope(clientId = newClientId, projectId = newProjectId)
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
            }
        }
    }

    override suspend fun getChatHistory(limit: Int, beforeSequence: Long?): ChatHistoryDto {
        val result = chatService.getHistory(limit = limit, beforeSequence = beforeSequence)

        val messages = result.messages.map { msg ->
            ChatMessageDto(
                role = when (msg.role) {
                    MessageRole.USER -> ChatRole.USER
                    MessageRole.ASSISTANT -> ChatRole.ASSISTANT
                    MessageRole.SYSTEM -> ChatRole.SYSTEM
                },
                content = msg.content,
                timestamp = msg.timestamp.toString(),
                correlationId = msg.correlationId,
                metadata = msg.metadata,
                sequence = msg.sequence,
            )
        }

        return ChatHistoryDto(
            messages = messages,
            hasMore = result.hasMore,
            oldestSequence = result.oldestSequence,
            activeClientId = result.activeClientId,
            activeProjectId = result.activeProjectId,
        )
    }

    override suspend fun updateScope(clientId: String?, projectId: String?) {
        if (clientId.isNullOrBlank()) return
        chatService.updateSessionScope(clientId = clientId, projectId = projectId)
        logger.info { "CHAT_SCOPE_UPDATE | clientId=$clientId | projectId=$projectId" }
    }

    override suspend fun archiveSession() {
        chatService.archiveCurrentSession()
        logger.info { "CHAT_SESSION_ARCHIVED" }
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
