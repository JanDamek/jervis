package com.jervis.service.chat

import com.jervis.entity.ChatMessageDocument
import com.jervis.entity.MessageRole
import com.jervis.repository.ChatMessageRepository
import com.jervis.types.TaskId
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * ChatMessageService - manages conversation messages.
 *
 * Responsibilities:
 * - Add new messages to conversation (with auto-increment sequence)
 * - Load messages for UI display (last N, pagination)
 * - Load full history for agent
 * - Search messages by content
 */
@Service
class ChatMessageService(
    private val chatMessageRepository: ChatMessageRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Add a new message to conversation.
     * Automatically assigns next sequence number.
     *
     * @param taskId Task this message belongs to
     * @param role Who sent the message (USER, ASSISTANT, SYSTEM)
     * @param content Message text
     * @param correlationId Trace ID for linking message to execution
     * @param metadata Additional message metadata
     * @return Saved message with assigned sequence number
     */
    suspend fun addMessage(
        taskId: TaskId,
        role: MessageRole,
        content: String,
        correlationId: String,
        metadata: Map<String, String> = emptyMap(),
    ): ChatMessageDocument {
        require(content.isNotBlank()) { "Message content cannot be blank" }

        // Get next sequence number
        val nextSequence = chatMessageRepository.countByTaskId(taskId) + 1

        val message =
            ChatMessageDocument(
                id = ObjectId(),
                taskId = taskId,
                correlationId = correlationId,
                role = role,
                content = content,
                timestamp = Instant.now(),
                sequence = nextSequence,
                metadata = metadata,
            )

        val saved = chatMessageRepository.save(message)

        logger.info {
            "MESSAGE_ADDED | taskId=$taskId | role=$role | sequence=$nextSequence | " +
                "contentLength=${content.length} | correlationId=$correlationId"
        }

        return saved
    }

    /**
     * Load last N messages for a task (for UI initial display).
     * Returns messages in chronological order (oldest first).
     *
     * @param taskId Task to load messages for
     * @param limit Maximum number of messages to load (default: 10)
     * @return List of messages in chronological order
     */
    suspend fun getLastMessages(
        taskId: TaskId,
        limit: Int = 10,
    ): List<ChatMessageDocument> {
        require(limit > 0) { "Limit must be positive" }

        // Repository returns DESC order, we reverse to get chronological
        val messages = chatMessageRepository.findTop10ByTaskIdOrderBySequenceDesc(taskId).toList()

        logger.debug { "MESSAGES_LOADED | taskId=$taskId | count=${messages.size} | limit=$limit" }

        return messages.reversed()
    }

    /**
     * Load all messages for a task (for agent to read full history).
     * Returns messages in chronological order.
     *
     * @param taskId Task to load messages for
     * @return List of all messages in chronological order
     */
    suspend fun getAllMessages(taskId: TaskId): List<ChatMessageDocument> {
        val messages = chatMessageRepository.findByTaskIdOrderBySequenceAsc(taskId).toList()

        logger.debug { "ALL_MESSAGES_LOADED | taskId=$taskId | count=${messages.size}" }

        return messages
    }

    /**
     * Load messages before a specific sequence (for pagination/"load more").
     * Used when user scrolls up in chat history.
     *
     * @param taskId Task to load messages for
     * @param beforeSequence Load messages with sequence < this value
     * @param limit Maximum number of messages to load
     * @return List of messages in chronological order
     */
    suspend fun getMessagesBeforeSequence(
        taskId: TaskId,
        beforeSequence: Long,
        limit: Int = 10,
    ): List<ChatMessageDocument> {
        require(limit > 0) { "Limit must be positive" }
        require(beforeSequence > 0) { "Sequence must be positive" }

        // Load DESC, then reverse for chronological order
        val messages =
            chatMessageRepository
                .findByTaskIdAndSequenceLessThanOrderBySequenceDesc(taskId, beforeSequence)
                .toList()
                .take(limit)

        logger.debug {
            "MESSAGES_BEFORE_SEQUENCE | taskId=$taskId | beforeSequence=$beforeSequence | " +
                "count=${messages.size} | limit=$limit"
        }

        return messages.reversed()
    }

    /**
     * Search messages by content (for agent search tool).
     * Returns all matching messages in chronological order.
     *
     * @param taskId Task to search in
     * @param searchText Text to search for (case-insensitive)
     * @return List of matching messages
     */
    suspend fun searchMessages(
        taskId: TaskId,
        searchText: String,
    ): List<ChatMessageDocument> {
        require(searchText.isNotBlank()) { "Search text cannot be blank" }

        val messages =
            chatMessageRepository
                .findByTaskIdAndContentContainingIgnoreCase(taskId, searchText)
                .toList()

        logger.info {
            "MESSAGES_SEARCHED | taskId=$taskId | searchText='$searchText' | found=${messages.size}"
        }

        return messages
    }

    /**
     * Delete all messages for a task.
     * Used when deleting a task.
     *
     * @param taskId Task to delete messages for
     * @return Number of messages deleted
     */
    suspend fun deleteAllMessages(taskId: TaskId): Long {
        val deletedCount = chatMessageRepository.deleteByTaskId(taskId)

        logger.info { "MESSAGES_DELETED | taskId=$taskId | count=$deletedCount" }

        return deletedCount
    }

    /**
     * Get message count for a task.
     *
     * @param taskId Task to count messages for
     * @return Number of messages
     */
    suspend fun getMessageCount(taskId: TaskId): Long {
        return chatMessageRepository.countByTaskId(taskId)
    }
}
