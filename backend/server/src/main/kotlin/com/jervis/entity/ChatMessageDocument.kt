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
 */
@Document(collection = "chat_messages")
@CompoundIndex(name = "conversation_sequence_idx", def = "{'conversationId': 1, 'sequence': -1}")
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
}
