package com.jervis.service

import com.jervis.dto.ChatHistoryDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseDto
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IAgentOrchestratorService {
    /**
     * Subscribe to chat session for given client and project.
     * Returns long-lived Flow of all chat responses for this session.
     * Multiple messages can arrive independently of send requests.
     */
    fun subscribeToChat(clientId: String, projectId: String): Flow<ChatResponseDto>

    /**
     * Send a chat message. Does not return responses - use subscribeToChat() to receive them.
     */
    suspend fun sendMessage(request: ChatRequestDto)

    /**
     * Retrieve last N messages from chat history for display.
     * Used when loading chat to show previous conversation.
     */
    suspend fun getChatHistory(clientId: String, projectId: String?, limit: Int = 10): ChatHistoryDto
}
