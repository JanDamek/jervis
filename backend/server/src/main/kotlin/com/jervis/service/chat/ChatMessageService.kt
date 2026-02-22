package com.jervis.service.chat

import com.jervis.entity.ChatMessageDocument
import com.jervis.entity.MessageRole
import com.jervis.repository.ChatMessageRepository
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
     */
    suspend fun addMessage(
        conversationId: ObjectId,
        role: MessageRole,
        content: String,
        correlationId: String,
        metadata: Map<String, String> = emptyMap(),
        clientMessageId: String? = null,
    ): ChatMessageDocument {
        require(content.isNotBlank()) { "Message content cannot be blank" }

        // Dedup check
        if (clientMessageId != null && chatMessageRepository.existsByClientMessageId(clientMessageId)) {
            logger.info { "MESSAGE_DEDUP | conversationId=$conversationId | clientMessageId=$clientMessageId" }
            // Return existing (find and return would be better, but for PoC this is fine)
        }

        val nextSequence = chatMessageRepository.countByConversationId(conversationId) + 1

        val message = ChatMessageDocument(
            id = ObjectId(),
            conversationId = conversationId,
            correlationId = correlationId,
            role = role,
            content = content,
            timestamp = Instant.now(),
            sequence = nextSequence,
            metadata = metadata,
            clientMessageId = clientMessageId,
        )

        val saved = chatMessageRepository.save(message)

        logger.info {
            "MESSAGE_ADDED | conversationId=$conversationId | role=$role | sequence=$nextSequence | " +
                "contentLength=${content.length} | correlationId=$correlationId"
        }

        return saved
    }

    /**
     * Load last N messages for a conversation (for UI initial display).
     * Returns messages in chronological order (oldest first).
     */
    suspend fun getLastMessages(
        conversationId: ObjectId,
        limit: Int = 10,
    ): List<ChatMessageDocument> {
        require(limit > 0) { "Limit must be positive" }

        val messages = chatMessageRepository.findTop10ByConversationIdOrderBySequenceDesc(conversationId).toList()

        logger.debug { "MESSAGES_LOADED | conversationId=$conversationId | count=${messages.size} | limit=$limit" }

        return messages.reversed()
    }

    /**
     * Load all messages for a conversation (for agent to read full history).
     * Returns messages in chronological order.
     */
    suspend fun getAllMessages(conversationId: ObjectId): List<ChatMessageDocument> {
        val messages = chatMessageRepository.findByConversationIdOrderBySequenceAsc(conversationId).toList()

        logger.debug { "ALL_MESSAGES_LOADED | conversationId=$conversationId | count=${messages.size}" }

        return messages
    }

    /**
     * Load messages before a specific sequence (for pagination/"load more").
     */
    suspend fun getMessagesBeforeSequence(
        conversationId: ObjectId,
        beforeSequence: Long,
        limit: Int = 10,
    ): List<ChatMessageDocument> {
        require(limit > 0) { "Limit must be positive" }
        require(beforeSequence > 0) { "Sequence must be positive" }

        val messages =
            chatMessageRepository
                .findByConversationIdAndSequenceLessThanOrderBySequenceDesc(conversationId, beforeSequence)
                .toList()
                .take(limit)

        logger.debug {
            "MESSAGES_BEFORE_SEQUENCE | conversationId=$conversationId | beforeSequence=$beforeSequence | " +
                "count=${messages.size} | limit=$limit"
        }

        return messages.reversed()
    }

    /**
     * Search messages by content (for agent search tool).
     */
    suspend fun searchMessages(
        conversationId: ObjectId,
        searchText: String,
    ): List<ChatMessageDocument> {
        require(searchText.isNotBlank()) { "Search text cannot be blank" }

        val messages =
            chatMessageRepository
                .findByConversationIdAndContentContainingIgnoreCase(conversationId, searchText)
                .toList()

        logger.info {
            "MESSAGES_SEARCHED | conversationId=$conversationId | searchText='$searchText' | found=${messages.size}"
        }

        return messages
    }

    /**
     * Delete all messages for a conversation.
     */
    suspend fun deleteAllMessages(conversationId: ObjectId): Long {
        val deletedCount = chatMessageRepository.deleteByConversationId(conversationId)

        logger.info { "MESSAGES_DELETED | conversationId=$conversationId | count=$deletedCount" }

        return deletedCount
    }

    /**
     * Get message count for a conversation.
     */
    suspend fun getMessageCount(conversationId: ObjectId): Long =
        chatMessageRepository.countByConversationId(conversationId)

    /**
     * Find a message by client-generated ID (for deduplication).
     */
    suspend fun findByClientMessageId(clientMessageId: String): ChatMessageDocument? =
        chatMessageRepository.findByClientMessageId(clientMessageId)
}
