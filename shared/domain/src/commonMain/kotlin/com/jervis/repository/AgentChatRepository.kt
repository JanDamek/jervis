package com.jervis.repository

import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import com.jervis.service.IAgentOrchestratorService
import kotlinx.coroutines.flow.Flow

/**
 * Repository for Agent Chat operations
 * Provides communication with the agent orchestrator
 */
class AgentChatRepository(
    private val agentOrchestratorService: IAgentOrchestratorService,
) : BaseRepository() {
    /**
     * Subscribe to long-lived chat stream for given client and project.
     * Returns Flow of all responses - can receive multiple messages per send.
     * Wrapped with error handling to prevent app crashes.
     */
    fun subscribeToChat(
        clientId: String,
        projectId: String,
    ): Flow<ChatResponseDto> =
        agentOrchestratorService
            .subscribeToChat(clientId, projectId)
            .safeFlow("subscribeToChat")

    /**
     * Send a chat message.
     * Responses arrive via subscribeToChat() Flow, not as return value.
     */
    suspend fun sendMessage(
        text: String,
        clientId: String,
        projectId: String,
        quick: Boolean = false,
    ) {
        val request = buildRequest(text, clientId, projectId, quick)
        safeRpcCall("sendMessage", returnNull = true) {
            agentOrchestratorService.sendMessage(request)
        }
    }

    private fun buildRequest(
        text: String,
        clientId: String,
        projectId: String,
        quick: Boolean,
    ): ChatRequestDto {
        val context = ChatRequestContextDto(
            clientId = clientId,
            projectId = projectId,
            quick = quick,
        )
        return ChatRequestDto(text = text, context = context)
    }

    /**
     * Send a user task instruction to the agent orchestrator.
     * Responses arrive via subscribeToChat() Flow.
     */
    suspend fun sendUserTaskInstruction(
        instruction: String,
        taskId: String,
        clientId: String,
        projectId: String,
    ) {
        // Prefix with task ID for traceability
        val enrichedInstruction = "User task $taskId: $instruction"

        sendMessage(
            text = enrichedInstruction,
            clientId = clientId,
            projectId = projectId,
            quick = false,
        )
    }
}
