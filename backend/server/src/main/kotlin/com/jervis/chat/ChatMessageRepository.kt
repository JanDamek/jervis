package com.jervis.chat

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
     * Load messages for a conversation, ordered by _id descending (newest first).
     * ObjectId is monotonically increasing, so _id order = insertion order.
     * Used with Flow.take(limit) for UI display.
     */
    suspend fun findByConversationIdOrderByIdDesc(conversationId: ObjectId): Flow<ChatMessageDocument>

    /**
     * Load all messages for a conversation, ordered by _id ascending.
     * Used by agent to read full conversation history.
     */
    suspend fun findByConversationIdOrderByIdAsc(conversationId: ObjectId): Flow<ChatMessageDocument>

    /**
     * Load messages before a specific message ID (for pagination / "load more").
     * Uses ObjectId comparison which is chronologically correct.
     */
    suspend fun findByConversationIdAndIdLessThanOrderByIdDesc(
        conversationId: ObjectId,
        id: ObjectId,
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
     * Count USER messages for a conversation after a given request time.
     * Used to detect inline messages sent while agent was orchestrating.
     * USER messages carry `requestTime`; non-USER roles use `responseTime`
     * which is irrelevant here (orchestrator polls for inline USER turns
     * arriving mid-flight).
     */
    suspend fun countByConversationIdAndRoleAndRequestTimeAfter(
        conversationId: ObjectId,
        role: MessageRole,
        requestTime: java.time.Instant,
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
     * Load messages excluding a specific role (newest first).
     * Used for background message filtering in chat history.
     */
    suspend fun findByConversationIdAndRoleNotOrderByIdDesc(
        conversationId: ObjectId,
        role: MessageRole,
    ): Flow<ChatMessageDocument>

    /**
     * Load messages before a cursor, excluding a specific role.
     * Used for filtered pagination.
     */
    suspend fun findByConversationIdAndRoleNotAndIdLessThanOrderByIdDesc(
        conversationId: ObjectId,
        role: MessageRole,
        id: ObjectId,
    ): Flow<ChatMessageDocument>

    /**
     * Count messages excluding a specific role.
     * Used for hasMore calculation with background filter.
     */
    suspend fun countByConversationIdAndRoleNot(conversationId: ObjectId, role: MessageRole): Long

    // ── Chat mode: exclude BACKGROUND but keep BACKGROUND with hasGraph=true ──
    // Important background results (with thinking graphs) must remain visible in chat mode.

    /**
     * Load chat messages: exclude BACKGROUND, but keep BACKGROUND with hasGraph=true (newest first).
     */
    @Query(
        value = "{ 'conversationId': ?0, '\$or': [ { 'role': { '\$ne': 'BACKGROUND' } }, { 'role': 'BACKGROUND', 'metadata.hasGraph': 'true' } ] }",
        sort = "{ '_id': -1 }",
    )
    suspend fun findChatWithGraphResults(conversationId: ObjectId): Flow<ChatMessageDocument>

    /**
     * Load chat messages before cursor: exclude BACKGROUND, but keep BACKGROUND with hasGraph=true.
     */
    @Query(
        value = "{ 'conversationId': ?0, '_id': { '\$lt': ?1 }, '\$or': [ { 'role': { '\$ne': 'BACKGROUND' } }, { 'role': 'BACKGROUND', 'metadata.hasGraph': 'true' } ] }",
        sort = "{ '_id': -1 }",
    )
    suspend fun findChatWithGraphResultsBefore(conversationId: ObjectId, id: ObjectId): Flow<ChatMessageDocument>

    /**
     * Count chat messages: exclude BACKGROUND, but keep BACKGROUND with hasGraph=true.
     */
    @Query(
        value = "{ 'conversationId': ?0, '\$or': [ { 'role': { '\$ne': 'BACKGROUND' } }, { 'role': 'BACKGROUND', 'metadata.hasGraph': 'true' } ] }",
        count = true,
    )
    suspend fun countChatWithGraphResults(conversationId: ObjectId): Long

    /**
     * Count messages by role.
     * Used for background message count badge.
     */
    suspend fun countByConversationIdAndRole(conversationId: ObjectId, role: MessageRole): Long

    /**
     * Load messages by specific role (newest first).
     * Used for "Tasky" filter — BACKGROUND-only messages.
     */
    suspend fun findByConversationIdAndRoleOrderByIdDesc(
        conversationId: ObjectId,
        role: MessageRole,
    ): Flow<ChatMessageDocument>

    /**
     * Load messages by specific role before a cursor (newest first).
     * Used for "Tasky" filter pagination.
     */
    suspend fun findByConversationIdAndRoleAndIdLessThanOrderByIdDesc(
        conversationId: ObjectId,
        role: MessageRole,
        id: ObjectId,
    ): Flow<ChatMessageDocument>

    /**
     * Count actionable BACKGROUND messages (needsReaction=true OR success=false, NOT dismissed).
     * Used for "K reakci" badge count — combined with USER_TASK count.
     */
    @Query(
        value = "{ 'conversationId': ?0, 'role': 'BACKGROUND', '\$or': [ { 'metadata.needsReaction': 'true' }, { 'metadata.success': 'false' } ], 'metadata.dismissed': { '\$ne': 'true' } }",
        count = true,
    )
    suspend fun countActionableBackground(conversationId: ObjectId): Long

    /**
     * Find actionable BACKGROUND messages (needsReaction=true OR success=false, NOT dismissed).
     * Used by dismissAll to mark them as dismissed.
     */
    @Query("{ 'conversationId': ?0, 'role': 'BACKGROUND', '\$or': [ { 'metadata.needsReaction': 'true' }, { 'metadata.success': 'false' } ], 'metadata.dismissed': { '\$ne': 'true' } }")
    fun findActionableBackground(conversationId: ObjectId): Flow<ChatMessageDocument>

    /**
     * Find chat messages by taskId in metadata.
     * Used by dismiss to mark BACKGROUND messages as dismissed when user clicks "Ignorovat".
     */
    @Query("{ 'metadata.taskId': ?0 }")
    fun findByMetadataTaskId(taskId: String): Flow<ChatMessageDocument>

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
