package com.jervis.repository

import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import com.jervis.service.IAgentOrchestratorService

/**
 * Repository for Agent Chat operations
 * Provides communication with the agent orchestrator
 */
class AgentChatRepository(
    private val agentOrchestratorService: IAgentOrchestratorService,
) {
    /**
     * Fire-and-forget: send a chat message to the agent orchestrator (202 Accepted path)
     */
    suspend fun sendMessage(
        text: String,
        clientId: String,
        projectId: String,
        quick: Boolean = false,
    ) {
        val request = buildRequest(text, clientId, projectId, quick)
        agentOrchestratorService.handle(request)
    }

    /**
     * Synchronous chat: send message and wait for final agent answer.
     */
    suspend fun sendAndWaitForAnswer(
        text: String,
        clientId: String,
        projectId: String,
        quick: Boolean = false,
    ): ChatResponseDto {
        val request = buildRequest(text, clientId, projectId, quick)
        return agentOrchestratorService.chat(request)
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
     * Send a user task instruction to the agent orchestrator
     */
    suspend fun sendUserTaskInstruction(
        instruction: String,
        taskId: String,
        clientId: String,
        projectId: String,
        wsSessionId: String? = null,
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
