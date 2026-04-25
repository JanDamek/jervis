package com.jervis.service.chat

import com.jervis.dto.chat.AttachmentDto
import com.jervis.dto.chat.ChatHistoryDto
import com.jervis.dto.chat.ChatMessageDto
import com.jervis.dto.chat.ChatResponseDto
import com.jervis.dto.chat.ChatThreadEvent
import com.jervis.dto.chat.SiriQueryResponse
import com.jervis.dto.chat.TtsChunkEvent
import com.jervis.dto.chat.VoiceAudioChunk
import com.jervis.dto.chat.VoiceChatEvent
import com.jervis.dto.chat.VoiceSessionChunk
import com.jervis.dto.chat.VoiceSessionConfig
import com.jervis.dto.chat.VoiceSessionEvent
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
     * Subscribe to a chat thread — push-only replacement for the
     * `getChatHistory` polling pattern.
     *
     * First emission is always [ChatThreadEvent.HistoryLoaded] with the
     * full chronological snapshot (or the slice newer than
     * `sinceMessageId` on resume). Every subsequent emission is one
     * [ChatThreadEvent.MessageAdded] as the server persists into
     * `chat_messages` for this `threadId`. The flow stays open until
     * the client cancels.
     *
     * `threadId` is the `conversationId` on `chat_messages` — for the
     * Claude session manager this is `ChatSessionDocument._id`; for
     * the LangGraph task path it is `TaskDocument._id`. UI passes
     * whichever id the surface owns.
     *
     * Per `architecture-push-only-streams.md`: the bubble list is built
     * by clearing on `HistoryLoaded` and appending on `MessageAdded`.
     * No mutable in-place edits of existing rows.
     */
    fun subscribeChatThread(
        threadId: String,
        sinceMessageId: String? = null,
    ): Flow<ChatThreadEvent>

    /**
     * Phase 5 chat-as-primary: load the message history of a single task's
     * conversation. The conversation id is `task.id.value` (every TaskDocument
     * already maintains its own thread). Used by the chat sidebar when the
     * user clicks on an active task and wants to continue the dialogue with
     * Jervis inside that task's thread instead of the main chat.
     *
     * Legacy path — left in place for the LangGraph-era pipeline. The Claude
     * CLI manager uses [getSessionConversationHistory] instead.
     */
    suspend fun getTaskConversationHistory(taskId: String, limit: Int = 200): ChatHistoryDto

    /**
     * Load the message history of a single chat session — the path the
     * Claude CLI manager uses. A ChatSessionDocument owns its thread via
     * `conversationId = session.id`, independent of any TaskDocument.
     *
     * Equivalent in shape to [getTaskConversationHistory] but keyed on the
     * session id. Returns empty history for unknown session ids.
     */
    suspend fun getSessionConversationHistory(sessionId: String, limit: Int = 200): ChatHistoryDto

    /**
     * Phase 5 draft persistence: save unsent text for a conversation.
     * Key null = main chat, taskId = task conversation.
     * Auto-saved by client every 10s when dirty. Restored on switch.
     */
    suspend fun saveDraft(conversationId: String?, text: String)

    /** Phase 5 draft persistence: load all drafts for restoration on startup. */
    suspend fun loadDrafts(): Map<String, String>

    /** Save a UI setting (e.g., sidebar width) — stored per-device, synced to server. */
    suspend fun saveUiSetting(key: String, value: String)

    /** Load all UI settings for the current device. */
    suspend fun loadUiSettings(): Map<String, String>

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

    /**
     * Stream TTS audio for a text — used by the chat bubble play button.
     *
     * Server bridges this kRPC stream to the TTS gRPC service. Emits one
     * HEADER event with the PCM sampleRate, followed by PCM events with
     * base64-encoded raw audio, terminating in DONE (or ERROR on failure).
     *
     * [activeClientId] / [activeProjectId] scope the normalization rule
     * lookup on the XTTS pod — project-scoped acronyms win over client,
     * client over global. Pass the currently active chat scope.
     */
    fun streamTts(
        text: String,
        activeClientId: String? = null,
        activeProjectId: String? = null,
    ): Flow<TtsChunkEvent>

    /**
     * One-shot text query from Siri / Google Assistant / Wear quick action.
     *
     * Creates a USER_TASK and polls until it reaches a terminal state (or timeout),
     * returning the response text. Voice assistants don't support streaming, so
     * this stays unary — but runs over kRPC same as any other RPC (no public REST).
     */
    suspend fun sendSiriQuery(
        query: String,
        source: String = "siri",
        clientId: String? = null,
        projectId: String? = null,
    ): SiriQueryResponse

    /**
     * Bidirectional live voice session — meeting live assist, helper hints.
     *
     * Client opens the session with config, then streams WAV chunks via `chunks`.
     * Server emits SESSION_STARTED (with sessionId), CHUNK_TRANSCRIBED partials,
     * HINT (knowledge-base suggestions), TOKEN / RESPONSE / TTS_AUDIO (if config.tts),
     * and terminates with DONE (or ERROR).
     *
     * Replaces legacy `/api/v1/voice/session` + `/voice/session/chunk` + `/voice/session/stop`.
     */
    fun streamVoiceSession(
        config: VoiceSessionConfig,
        chunks: Flow<VoiceSessionChunk>,
    ): Flow<VoiceSessionEvent>
}
