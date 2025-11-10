package com.jervis.repository

import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatRequestDto
import com.jervis.service.IAgentOrchestratorService

/**
 * Repository for Agent Chat operations
 * Provides communication with the agent orchestrator
 */
class AgentChatRepository(
    private val agentOrchestratorService: IAgentOrchestratorService
) {
    /**
     * Send a chat message to the agent orchestrator
     */
    suspend fun sendMessage(
        text: String,
        clientId: String,
        projectId: String,
        wsSessionId: String? = null,
        quick: Boolean = false,
        autoScope: Boolean = false,
        confirmedScope: Boolean = false,
        existingContextId: String? = null
    ) {
        val context = ChatRequestContextDto(
            clientId = clientId,
            projectId = projectId,
            autoScope = autoScope,
            quick = quick,
            confirmedScope = confirmedScope,
            existingContextId = existingContextId
        )

        val request = ChatRequestDto(
            text = text,
            context = context,
            wsSessionId = wsSessionId
        )

        agentOrchestratorService.handle(request)
    }

    /**
     * Send a user task instruction to the agent orchestrator
     */
    suspend fun sendUserTaskInstruction(
        instruction: String,
        taskId: String,
        clientId: String,
        projectId: String,
        wsSessionId: String? = null
    ) {
        // Prefix with task ID for traceability
        val enrichedInstruction = "User task $taskId: $instruction"

        sendMessage(
            text = enrichedInstruction,
            clientId = clientId,
            projectId = projectId,
            wsSessionId = wsSessionId,
            quick = false
        )
    }
}
