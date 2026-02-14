package com.jervis.entity

import com.jervis.common.types.TaskId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * ChatMessageDocument - single message in a conversation.
 *
 * NEW ARCHITECTURE:
 * - Messages are stored in a separate collection (not embedded in TaskDocument)
 * - Each message links to a TaskDocument via taskId
 * - Sequence number ensures correct ordering within a task
 * - Supports efficient pagination: load last 10, then "load more" on demand
 *
 * Benefits:
 * - UI can load last 10 messages quickly
 * - Pagination works efficiently with large conversation histories
 * - Agent can search/filter messages without loading entire task
 *
 * @property id MongoDB ObjectId (auto-generated)
 * @property taskId Reference to TaskDocument (the conversation thread)
 * @property correlationId Trace ID for linking message to execution
 * @property role Who sent the message: USER, ASSISTANT, SYSTEM
 * @property content Message text
 * @property timestamp When the message was created
 * @property sequence Order within task (auto-incremented, 1, 2, 3, ...)
 * @property metadata Additional message metadata (e.g., model used, tokens, etc.)
 */
@Document(collection = "chat_messages")
@CompoundIndex(name = "task_sequence_idx", def = "{'taskId': 1, 'sequence': -1}")
data class ChatMessageDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed
    val taskId: TaskId,
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
