package com.jervis.service.chat

import com.jervis.entity.ChatMessageDocument
import com.jervis.entity.ChatSessionDocument
import com.jervis.entity.MessageRole
import com.jervis.repository.ChatSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * ChatService — manages foreground chat sessions and message flow.
 *
 * Responsibilities:
 * - Session lifecycle (get/create active session, archive)
 * - Accept user message: save to DB, forward to Python /chat, stream SSE back
 * - Load chat history for UI
 *
 * One active (non-archived) session per user. Messages flow:
 * UI → kRPC → ChatService.sendMessage() → PythonChatClient.chat() → SSE events → UI
 */
@Service
class ChatService(
    private val chatSessionRepository: ChatSessionRepository,
    private val chatMessageService: ChatMessageService,
    private val pythonChatClient: PythonChatClient,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Get or create the active (non-archived) session for a user.
     * At most one active session exists per user.
     */
    suspend fun getOrCreateActiveSession(userId: String = "jan"): ChatSessionDocument {
        val existing = chatSessionRepository.findFirstByUserIdAndArchivedOrderByLastMessageAtDesc(userId, false)
        if (existing != null) {
            logger.debug { "ACTIVE_SESSION_FOUND | userId=$userId | sessionId=${existing.id}" }
            return existing
        }

        val session = ChatSessionDocument(userId = userId)
        val saved = chatSessionRepository.save(session)
        logger.info { "NEW_SESSION_CREATED | userId=$userId | sessionId=${saved.id}" }
        return saved
    }

    /**
     * Send a user message in the foreground chat.
     *
     * Flow:
     * 1. Get/create active session
     * 2. Save user message to MongoDB (with sequence)
     * 3. Update session lastMessageAt
     * 4. Forward to Python /chat SSE endpoint
     * 5. Return Flow of ChatStreamEvent for UI streaming
     *
     * @return Flow of SSE events from Python (token, tool_call, tool_result, done, error)
     */
    suspend fun sendMessage(
        userId: String = "jan",
        text: String,
        clientMessageId: String? = null,
        activeClientId: String? = null,
        activeProjectId: String? = null,
        activeGroupId: String? = null,
        contextTaskId: String? = null,
        maxOpenRouterTier: String = "NONE",
    ): Flow<ChatStreamEvent> {
        require(text.isNotBlank()) { "Message text cannot be blank" }

        // 1. Get/create session
        val session = getOrCreateActiveSession(userId)
        val sessionId = session.id

        // 2. Dedup check — return confirmation event instead of empty flow
        if (clientMessageId != null) {
            val existingMessage = chatMessageService.findByClientMessageId(clientMessageId)
            if (existingMessage != null) {
                logger.info { "CHAT_DEDUP | sessionId=$sessionId | clientMessageId=$clientMessageId" }
                return kotlinx.coroutines.flow.flow {
                    emit(ChatStreamEvent(type = "done", metadata = mapOf("deduplicated" to true, "existingSequence" to existingMessage.sequence)))
                }
            }
        }

        // 3. Save user message (include contextTaskId in metadata for visual link in UI)
        val correlationId = ObjectId().toString()
        val messageMetadata = if (contextTaskId != null) mapOf("contextTaskId" to contextTaskId) else emptyMap()
        val savedMessage = chatMessageService.addMessage(
            conversationId = sessionId,
            role = MessageRole.USER,
            content = text,
            correlationId = correlationId,
            clientMessageId = clientMessageId,
            metadata = messageMetadata,
        )

        // 4. Update session (including active scope for UI restore on restart)
        session.lastMessageAt = Instant.now()
        if (session.title == null) {
            session.title = text.take(80)
        }
        if (activeClientId != null) {
            session.lastClientId = activeClientId
            session.lastProjectId = activeProjectId
            session.lastGroupId = activeGroupId
        }
        chatSessionRepository.save(session)

        logger.info {
            "CHAT_MESSAGE_SAVED | sessionId=$sessionId | sequence=${savedMessage.sequence} | " +
                "clientId=$activeClientId | projectId=$activeProjectId | textLen=${text.length}"
        }

        // 5. Forward to Python /chat and return SSE stream
        return pythonChatClient.chat(
            sessionId = sessionId.toString(),
            message = text,
            messageSequence = savedMessage.sequence,
            userId = userId,
            activeClientId = activeClientId,
            activeProjectId = activeProjectId,
            activeGroupId = activeGroupId,
            contextTaskId = contextTaskId,
            maxOpenRouterTier = maxOpenRouterTier,
        )
    }

    /**
     * Load chat history for UI display.
     * Pagination uses message ObjectId (monotonically increasing) instead of sequence
     * to avoid issues with sequence counter desync between Kotlin and Python.
     */
    suspend fun getHistory(
        userId: String = "jan",
        limit: Int = 20,
        beforeMessageId: String? = null,
        excludeBackground: Boolean = false,
    ): ChatHistoryResult {
        val session = chatSessionRepository.findFirstByUserIdAndArchivedOrderByLastMessageAtDesc(userId, false)
            ?: return ChatHistoryResult(messages = emptyList(), hasMore = false)

        val messages = if (beforeMessageId != null) {
            chatMessageService.getMessagesBefore(session.id, ObjectId(beforeMessageId), limit, excludeBackground)
        } else {
            chatMessageService.getLastMessages(session.id, limit, excludeBackground)
        }

        val totalCount = chatMessageService.getMessageCount(session.id, excludeBackground)
        val hasMore = messages.size == limit && messages.size.toLong() < totalCount

        // Count background messages for badge (only when they're excluded from results)
        val backgroundCount = if (excludeBackground) {
            chatMessageService.countByRole(session.id, com.jervis.entity.MessageRole.BACKGROUND).toInt()
        } else 0

        return ChatHistoryResult(
            sessionId = session.id.toString(),
            messages = messages,
            hasMore = hasMore,
            oldestMessageId = messages.firstOrNull()?.id?.toString(),
            totalCount = totalCount,
            activeClientId = session.lastClientId,
            activeProjectId = session.lastProjectId,
            activeGroupId = session.lastGroupId,
            backgroundMessageCount = backgroundCount,
        )
    }

    /**
     * Update session scope when Python emits scope_change.
     * Called by ChatRpcImpl when it sees a SCOPE_CHANGE event.
     */
    suspend fun updateSessionScope(userId: String = "jan", clientId: String, projectId: String?, groupId: String? = null) {
        val session = chatSessionRepository.findFirstByUserIdAndArchivedOrderByLastMessageAtDesc(userId, false) ?: return
        session.lastClientId = clientId
        // Only overwrite projectId/groupId if provided — prevents reconnect from clearing saved scope
        if (projectId != null) {
            session.lastProjectId = projectId
            session.lastGroupId = groupId
        }
        chatSessionRepository.save(session)
        logger.info { "SESSION_SCOPE_UPDATE | sessionId=${session.id} | clientId=$clientId | projectId=$projectId | groupId=$groupId" }
    }

    /**
     * Save a system-generated message (BACKGROUND result, ALERT, etc.) to DB.
     * Used by ChatRpcImpl push methods to persist messages before emitting to stream.
     */
    suspend fun saveSystemMessage(
        sessionId: ObjectId,
        role: MessageRole,
        content: String,
        metadata: Map<String, String> = emptyMap(),
    ): ChatMessageDocument {
        val correlationId = ObjectId().toString()
        return chatMessageService.addMessage(
            conversationId = sessionId,
            role = role,
            content = content,
            correlationId = correlationId,
            metadata = metadata,
        )
    }

    /**
     * Archive the current session (new session created on next message).
     */
    suspend fun archiveCurrentSession(userId: String = "jan") {
        val session = chatSessionRepository.findFirstByUserIdAndArchivedOrderByLastMessageAtDesc(userId, false)
        if (session != null) {
            session.archived = true
            chatSessionRepository.save(session)
            logger.info { "SESSION_ARCHIVED | userId=$userId | sessionId=${session.id}" }
        }
    }

    /**
     * List archived sessions for history browsing.
     */
    fun getArchivedSessions(userId: String = "jan"): Flow<ChatSessionDocument> {
        return chatSessionRepository.findByUserIdAndArchivedOrderByLastMessageAtDesc(userId, true)
    }

    /**
     * Approve or deny a pending chat tool action.
     * Forwards to Python /chat/approve endpoint.
     */
    suspend fun approveChatAction(
        userId: String = "jan",
        approved: Boolean,
        always: Boolean = false,
        action: String? = null,
    ) {
        val session = getOrCreateActiveSession(userId)
        pythonChatClient.approveAction(
            sessionId = session.id.toString(),
            approved = approved,
            always = always,
            action = action,
        )
    }
}

/**
 * Result of loading chat history.
 */
data class ChatHistoryResult(
    val sessionId: String? = null,
    val messages: List<ChatMessageDocument>,
    val hasMore: Boolean,
    val oldestMessageId: String? = null,
    val totalCount: Long = 0,
    val activeClientId: String? = null,
    val activeProjectId: String? = null,
    val activeGroupId: String? = null,
    val backgroundMessageCount: Int = 0,
)
