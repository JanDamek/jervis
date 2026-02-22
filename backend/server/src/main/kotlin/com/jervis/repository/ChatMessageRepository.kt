package com.jervis.repository

import com.jervis.entity.ChatMessageDocument
import com.jervis.entity.MessageRole
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * ChatMessageRepository - access to conversation messages.
 *
 * Key operations:
 * - Load last N messages (for UI initial display)
 * - Load messages in range (for pagination/"load more")
 * - Load all messages (for agent to read full history)
 * - Search messages by content (for agent search tool)
 * - Count messages (to get next sequence number)
 */
@Repository
interface ChatMessageRepository : CoroutineCrudRepository<ChatMessageDocument, ObjectId> {
    /**
     * Load last N messages for a conversation, ordered by sequence descending.
     * Used for initial UI display (show last 10 messages).
     *
     * NOTE: Returns in DESCENDING order (newest first), caller should reverse if needed.
     */
    suspend fun findTop10ByConversationIdOrderBySequenceDesc(conversationId: ObjectId): Flow<ChatMessageDocument>

    /**
     * Load all messages for a conversation, ordered by sequence ascending.
     * Used by agent to read full conversation history.
     */
    suspend fun findByConversationIdOrderBySequenceAsc(conversationId: ObjectId): Flow<ChatMessageDocument>

    /**
     * Load messages before a specific sequence number (for pagination).
     * Used for "load more" functionality in UI.
     */
    suspend fun findByConversationIdAndSequenceLessThanOrderBySequenceDesc(
        conversationId: ObjectId,
        sequence: Long,
    ): Flow<ChatMessageDocument>

    /**
     * Search messages by content (case-insensitive).
     * Used by agent search tool to find specific information in history.
     */
    suspend fun findByConversationIdAndContentContainingIgnoreCase(
        conversationId: ObjectId,
        searchText: String,
    ): Flow<ChatMessageDocument>

    /**
     * Count messages for a conversation.
     * Used to generate next sequence number when adding new message.
     */
    suspend fun countByConversationId(conversationId: ObjectId): Long

    /**
     * Count USER messages for a conversation after a given timestamp.
     * Used to detect inline messages sent while agent was orchestrating.
     */
    suspend fun countByConversationIdAndRoleAndTimestampAfter(
        conversationId: ObjectId,
        role: MessageRole,
        timestamp: java.time.Instant,
    ): Long

    /**
     * Delete all messages for a conversation.
     * Used when deleting a task/session.
     */
    suspend fun deleteByConversationId(conversationId: ObjectId): Long

    /**
     * Find messages by conversation and role.
     */
    @Query("{ 'conversationId': ?0, 'role': ?1 }")
    suspend fun findByConversationIdAndRole(
        conversationId: ObjectId,
        role: MessageRole,
    ): Flow<ChatMessageDocument>

    /**
     * Find messages by content search (case-insensitive).
     */
    @Query("{ 'conversationId': ?0, 'content': { '\$regex': ?1, '\$options': 'i' } }")
    suspend fun findByConversationIdAndContentRegex(
        conversationId: ObjectId,
        searchText: String,
    ): Flow<ChatMessageDocument>

    /**
     * Check if a message with the given client-generated ID already exists.
     * Used for deduplication — prevents duplicate processing on retry.
     */
    suspend fun existsByClientMessageId(clientMessageId: String): Boolean

    /**
     * Find a message by client-generated ID.
     * Used for deduplication — returns existing message on retry.
     */
    suspend fun findByClientMessageId(clientMessageId: String): ChatMessageDocument?
}
