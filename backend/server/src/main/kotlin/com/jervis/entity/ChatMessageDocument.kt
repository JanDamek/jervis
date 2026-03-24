package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * ChatMessageDocument - single message in a conversation.
 *
 * Messages are stored in a separate collection (not embedded in TaskDocument).
 * Each message links to a conversation thread via conversationId:
 * - For foreground chat: conversationId = ChatSession._id
 * - For background tasks: conversationId = TaskDocument._id
 *
 * Supports efficient pagination: load last 10, then "load more" on demand.
 *
 * @property id MongoDB ObjectId (auto-generated)
 * @property conversationId Reference to conversation thread (ChatSession or TaskDocument)
 * @property correlationId Trace ID for linking message to execution
 * @property role Who sent the message: USER, ASSISTANT, SYSTEM
 * @property content Message text
 * @property timestamp When the message was created
 * @property sequence Order within conversation (auto-incremented, 1, 2, 3, ...)
 * @property metadata Additional message metadata (e.g., model used, tokens, etc.)
 * @property clientMessageId Client-generated ID for deduplication on retry
 * @property clientId Client scope when message was created (null = unscoped / legacy)
 * @property projectId Project scope when message was created
 * @property groupId Group scope when message was created
 * @property parentRequestId For sub-requests: ID of the master request that was decomposed
 * @property isDecomposed True if this master request was decomposed into sub-requests
 * @property subRequestIds IDs of sub-request messages (for master messages)
 * @property affectedScopes All client/project scopes this master touches (for cross-context query)
 */
@Document(collection = "chat_messages")
@CompoundIndex(name = "conversation_sequence_idx", def = "{'conversationId': 1, 'sequence': -1}")
@CompoundIndex(name = "scope_idx", def = "{'conversationId': 1, 'clientId': 1, 'projectId': 1, '_id': -1}")
data class ChatMessageDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed
    val conversationId: ObjectId,
    @Indexed
    val correlationId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Instant = Instant.now(),
    @Indexed
    val sequence: Long,
    val metadata: Map<String, String> = emptyMap(),
    @Indexed
    val clientMessageId: String? = null,
    // Scope fields — which client/project context was active when message was created
    @Indexed
    val clientId: String? = null,
    val projectId: String? = null,
    val groupId: String? = null,
    // Cross-context decomposition fields
    val parentRequestId: String? = null,
    val isDecomposed: Boolean = false,
    val subRequestIds: List<String> = emptyList(),
    val affectedScopes: List<ScopeRef> = emptyList(),
    // Computed by aggregation pipeline — not persisted, set by $addFields
    @org.springframework.data.annotation.Transient
    val isOutOfScope: Boolean = false,
)

/**
 * Reference to a client/project scope — used in affectedScopes for cross-context master messages.
 */
data class ScopeRef(
    val clientId: String,
    val projectId: String? = null,
)

/**
 * MessageRole - who sent the message.
 *
 * - USER: Message from user (via UI, email, Jira, etc.)
 * - ASSISTANT: Response from AI agent
 * - SYSTEM: System-generated message (e.g., "Task started", "Error occurred")
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    BACKGROUND,  // Result from a completed background task
    ALERT,       // Urgent notification (e.g., urgent email, deadline approaching)
}
