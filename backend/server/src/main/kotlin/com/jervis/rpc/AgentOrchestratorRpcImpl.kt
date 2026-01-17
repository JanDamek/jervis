package com.jervis.rpc

import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import com.jervis.mapper.toDomain
import com.jervis.service.IAgentOrchestratorService
import com.jervis.service.agent.coordinator.AgentOrchestratorService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class AgentOrchestratorRpcImpl(
    private val agentOrchestratorService: AgentOrchestratorService,
) : IAgentOrchestratorService {
    private val logger = KotlinLogging.logger {}

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
        logger.info { "Received message for session $sessionKey: ${request.text}" }

        try {
            // Ensure stream exists (create if needed)
            val stream = chatStreams.getOrPut(sessionKey) {
                logger.info { "Creating new chat stream for session $sessionKey" }
                MutableSharedFlow(
                    replay = 10,
                    extraBufferCapacity = 50,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
            }

            // Process message through orchestrator
            val response = agentOrchestratorService.handle(
                text = request.text,
                ctx = request.context.toDomain(),
            )

            // Emit response to the session's stream
            logger.debug { "Emitting response to stream $sessionKey" }
            stream.emit(ChatResponseDto(message = response.message))
        } catch (e: Exception) {
            logger.error(e) { "Error processing message for session $sessionKey" }
            // Emit error response to stream
            val stream = chatStreams[sessionKey]
            if (stream != null) {
                stream.emit(ChatResponseDto(message = "Error: ${e.message ?: "Unknown error"}"))
            }
            throw e
        }
    }
}
