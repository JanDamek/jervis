package com.jervis.service.chat

import com.jervis.dto.chat.AttachmentDto
import com.jervis.dto.chat.ChatHistoryDto
import com.jervis.dto.chat.ChatMessageDto
import com.jervis.dto.chat.ChatResponseDto
import com.jervis.dto.chat.VoiceAudioChunk
import com.jervis.dto.chat.VoiceChatEvent
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
        tierOverride: String? = null,
        clientTimezone: String? = null,
    )

    /**
     * Load chat history for UI display (pagination).
     * Filters are independent toggles — server builds DB query from combination.
     * @param limit Max messages to return
     * @param beforeMessageId Pagination cursor
     * @param showChat Include user/assistant messages
     * @param showTasks Include ALL background task results
     * @param showNeedReaction Include actionable items (failed background + USER_TASK with question)
     */
    suspend fun getChatHistory(
        limit: Int = 20,
        beforeMessageId: String? = null,
        showChat: Boolean = true,
        showTasks: Boolean = false,
        showNeedReaction: Boolean = true,
        filterClientId: String? = null,
        filterProjectId: String? = null,
        filterGroupId: String? = null,
    ): ChatHistoryDto

    /**
     * Phase 5 chat-as-primary: load the message history of a single task's
     * conversation. The conversation id is `task.id.value` (every TaskDocument
     * already maintains its own thread). Used by the chat sidebar when the
     * user clicks on an active task and wants to continue the dialogue with
     * Jervis inside that task's thread instead of the main chat.
     */
    suspend fun getTaskConversationHistory(taskId: String, limit: Int = 200): ChatHistoryDto

    /**
     * Update active scope (client/project/group) in the chat session.
     * Called when user manually switches client/project/group in the UI dropdown.
     */
    suspend fun updateScope(clientId: String?, projectId: String?, groupId: String? = null)

    /**
     * Dismiss all actionable items (BACKGROUND messages + USER_TASKs).
     * Marks BACKGROUND messages as dismissed in chat_messages, moves USER_TASKs to DONE.
     * Returns total count of dismissed items.
     */
    suspend fun dismissAllActionable(): Int

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

    /**
     * Streaming voice chat — bidirectional kRPC stream over WebSocket.
     *
     * Client streams audio chunks (base64 PCM/WAV) as user speaks.
     * Server accumulates audio, runs Whisper STT, sends to orchestrator,
     * and streams back events: transcription, response tokens, TTS audio.
     *
     * Latency: transcription starts immediately when audio arrives,
     * so response comes ~2-3s after user stops speaking.
     */
    fun streamVoiceChat(audioChunks: Flow<VoiceAudioChunk>): Flow<VoiceChatEvent>
}
