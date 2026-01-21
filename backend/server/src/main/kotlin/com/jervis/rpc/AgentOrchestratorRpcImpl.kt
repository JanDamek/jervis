package com.jervis.rpc

import com.jervis.dto.ChatHistoryDto
import com.jervis.dto.ChatMessageDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import com.jervis.mapper.toDomain
import com.jervis.repository.ChatSessionRepository
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class AgentOrchestratorRpcImpl(
    private val agentOrchestratorService: AgentOrchestratorService,
    private val chatSessionRepository: ChatSessionRepository,
) : IAgentOrchestratorService {
    private val logger = KotlinLogging.logger {}
    private val backgroundScope = CoroutineScope(Dispatchers.Default)

    // Store active chat streams per session (clientId + projectId)
    private val chatStreams = ConcurrentHashMap<String, MutableSharedFlow<ChatResponseDto>>()

    override fun subscribeToChat(clientId: String, projectId: String): Flow<ChatResponseDto> {
        val sessionKey = "$clientId:$projectId"
        logger.info { "Client subscribing to chat stream: $sessionKey" }

        // Get or create shared flow for this session
        val sharedFlow = chatStreams.getOrPut(sessionKey) {
            MutableSharedFlow(
                replay = 10, // Keep last 10 messages for late subscribers
                extraBufferCapacity = 50,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }

        // Return flow that FIRST emits history, THEN live updates
        return kotlinx.coroutines.flow.flow {
            // 1. Load and emit chat history IMMEDIATELY when client subscribes
            try {
                val history = getChatHistory(clientId, projectId, limit = 50)
                logger.info { "EMITTING_HISTORY | session=$sessionKey | messages=${history.messages.size}" }

                history.messages.forEach { msg ->
                    val responseType = when (msg.role) {
                        "user" -> com.jervis.dto.ChatResponseType.USER_MESSAGE
                        else -> com.jervis.dto.ChatResponseType.FINAL
                    }

                    emit(ChatResponseDto(
                        message = msg.content,
                        type = responseType,
                        metadata = mapOf(
                            "sender" to msg.role,
                            "timestamp" to msg.timestamp,
                            "correlationId" to (msg.correlationId ?: ""),
                            "fromHistory" to "true"
                        )
                    ))
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load history for session=$sessionKey" }
            }

            // 2. Then emit all LIVE updates from SharedFlow
            sharedFlow.collect { response ->
                emit(response)
            }
        }
    }

    override suspend fun sendMessage(request: ChatRequestDto) {
        val sessionKey = "${request.context.clientId}:${request.context.projectId}"
        logger.info { "RPC_MESSAGE_RECEIVED | session=$sessionKey | text='${request.text.take(100)}'" }

        // Ensure stream exists (create if needed)
        val stream = chatStreams.getOrPut(sessionKey) {
            logger.info { "STREAM_CREATED | session=$sessionKey" }
            MutableSharedFlow(
                replay = 10,
                extraBufferCapacity = 50,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }

        // IMMEDIATELY emit user message to ALL clients listening on this session (SYNCHRONOUSLY!)
        // This ensures synchronization across Desktop/iOS/Android
        kotlinx.coroutines.runBlocking {
            stream.emit(ChatResponseDto(
                message = request.text,
                type = com.jervis.dto.ChatResponseType.USER_MESSAGE,
                metadata = mapOf(
                    "sender" to "user",
                    "clientId" to request.context.clientId,
                    "timestamp" to java.time.Instant.now().toString()
                )
            ))
            logger.info { "USER_MESSAGE_EMITTED | session=$sessionKey | text='${request.text.take(50)}'" }
        }

        // CRITICAL FIX: Launch async processing in background to prevent RPC timeout
        // This allows sendMessage() to return immediately while agent runs in background
        backgroundScope.launch {
            try {
                logger.info { "AGENT_PROCESSING_START | session=$sessionKey" }

                // Process message through orchestrator with progress callback
                val response = agentOrchestratorService.handle(
                    text = request.text,
                    ctx = request.context.toDomain(),
                    onProgress = { message, metadata ->
                        // Emit progress update to stream
                        stream.emit(ChatResponseDto(
                            message = message,
                            type = com.jervis.dto.ChatResponseType.PROGRESS,
                            metadata = metadata
                        ))
                        logger.debug { "PROGRESS_UPDATE | session=$sessionKey | msg=${message.take(50)}" }
                    }
                )

                // Emit final response to the session's stream
                logger.info { "AGENT_PROCESSING_COMPLETE | session=$sessionKey | responseLength=${response.message.length}" }
                stream.emit(ChatResponseDto(
                    message = response.message,
                    type = com.jervis.dto.ChatResponseType.FINAL
                ))
            } catch (e: Exception) {
                logger.error(e) { "AGENT_PROCESSING_FAILED | session=$sessionKey | error=${e.message}" }
                // Emit error response to stream
                stream.emit(ChatResponseDto(
                    message = "Error: ${e.message ?: "Unknown error"}",
                    type = com.jervis.dto.ChatResponseType.FINAL
                ))
            }
        }

        // Return immediately - response will arrive via subscribeToChat() stream
        logger.info { "RPC_MESSAGE_ACCEPTED | session=$sessionKey (processing in background)" }
    }

    override suspend fun getChatHistory(clientId: String, projectId: String?, limit: Int): ChatHistoryDto {
        val clientIdTyped = ClientId.fromString(clientId)
        val projectIdTyped = projectId?.let { ProjectId.fromString(it) }

        logger.info { "CHAT_HISTORY_REQUEST | clientId=$clientId | projectId=$projectId | limit=$limit" }

        val session = chatSessionRepository.findByClientIdAndProjectId(clientIdTyped, projectIdTyped)

        if (session == null) {
            logger.info { "CHAT_HISTORY_NOT_FOUND | clientId=$clientId | projectId=$projectId" }
            return ChatHistoryDto(messages = emptyList())
        }

        // Get last N messages
        val messages = session.messages.takeLast(limit).map { msg ->
            ChatMessageDto(
                role = msg.role,
                content = msg.content,
                timestamp = msg.timestamp.toString(),
                correlationId = msg.correlationId,
            )
        }

        logger.info { "CHAT_HISTORY_FOUND | clientId=$clientId | projectId=$projectId | messageCount=${messages.size}" }
        return ChatHistoryDto(messages = messages)
    }
}
