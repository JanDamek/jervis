package com.jervis.service

import com.jervis.dto.AttachmentDto
import com.jervis.dto.ChatHistoryDto
import com.jervis.dto.ChatMessageDto
import com.jervis.dto.ChatResponseDto
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * IChatService — foreground chat RPC interface.
 *
 * Global chat (not per client/project). One active session per user.
 * Messages streamed via subscribeToChatEvents().
 */
@Rpc
interface IChatService {

    /**
     * Subscribe to real-time chat events (SSE from Python forwarded to UI).
     * Returns Flow of ChatResponseDto: tokens, tool calls, tool results, done, error.
     */
    fun subscribeToChatEvents(): Flow<ChatResponseDto>

    /**
     * Send a user message. Triggers Python /chat agentic loop.
     * Responses arrive via subscribeToChatEvents().
     *
     * @param text Message text
     * @param clientMessageId Client-generated UUID for dedup
     * @param activeClientId Currently selected client in UI (scope)
     * @param activeProjectId Currently selected project in UI (scope)
     * @param activeGroupId Currently selected group in UI (scope)
     * @param contextTaskId If responding to a specific user_task
     */
    suspend fun sendMessage(
        text: String,
        clientMessageId: String? = null,
        activeClientId: String? = null,
        activeProjectId: String? = null,
        activeGroupId: String? = null,
        contextTaskId: String? = null,
        attachments: List<AttachmentDto> = emptyList(),
    )

    /**
     * Load chat history for UI display (pagination).
     * @param limit Max messages to return
     * @param beforeMessageId Load messages before this message ID (cursor, ObjectId string)
     */
    suspend fun getChatHistory(limit: Int = 20, beforeMessageId: String? = null, excludeBackground: Boolean = true): ChatHistoryDto

    /**
     * Update active scope (client/project/group) in the chat session.
     * Called when user manually switches client/project/group in the UI dropdown.
     */
    suspend fun updateScope(clientId: String?, projectId: String?, groupId: String? = null)

    /**
     * Archive current session (starts fresh on next message).
     */
    suspend fun archiveSession()

    /**
     * Approve or deny a pending chat tool action.
     * Called when user clicks Approve/Deny in the approval dialog.
     *
     * @param approved True if user approved, false if denied
     * @param always True if "approve always" for this action type in this session
     * @param action The approval action type (e.g. "KB_DELETE")
     */
    suspend fun approveChatAction(approved: Boolean, always: Boolean = false, action: String? = null)
}
