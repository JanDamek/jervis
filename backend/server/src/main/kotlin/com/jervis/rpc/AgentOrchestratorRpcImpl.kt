package com.jervis.rpc

import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import com.jervis.mapper.toDomain
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
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

        return sharedFlow.asSharedFlow()
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

        // CRITICAL FIX: Launch async processing in background to prevent RPC timeout
        // This allows sendMessage() to return immediately while agent runs in background
        backgroundScope.launch {
            try {
                logger.info { "AGENT_PROCESSING_START | session=$sessionKey" }

                // Process message through orchestrator (can take >60s)
                val response = agentOrchestratorService.handle(
                    text = request.text,
                    ctx = request.context.toDomain(),
                )

                // Emit response to the session's stream
                logger.info { "AGENT_PROCESSING_COMPLETE | session=$sessionKey | responseLength=${response.message.length}" }
                stream.emit(ChatResponseDto(message = response.message))
            } catch (e: Exception) {
                logger.error(e) { "AGENT_PROCESSING_FAILED | session=$sessionKey | error=${e.message}" }
                // Emit error response to stream
                stream.emit(ChatResponseDto(message = "Error: ${e.message ?: "Unknown error"}"))
            }
        }

        // Return immediately - response will arrive via subscribeToChat() stream
        logger.info { "RPC_MESSAGE_ACCEPTED | session=$sessionKey (processing in background)" }
    }
}
