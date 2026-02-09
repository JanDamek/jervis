package com.jervis.repository

import com.jervis.common.types.TaskId
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
     * Load last N messages for a task, ordered by sequence descending.
     * Used for initial UI display (show last 10 messages).
     *
     * NOTE: Returns in DESCENDING order (newest first), caller should reverse if needed.
     */
    suspend fun findTop10ByTaskIdOrderBySequenceDesc(taskId: TaskId): Flow<ChatMessageDocument>

    /**
     * Load all messages for a task, ordered by sequence ascending.
     * Used by agent to read full conversation history.
     */
    suspend fun findByTaskIdOrderBySequenceAsc(taskId: TaskId): Flow<ChatMessageDocument>

    /**
     * Load messages after a specific sequence number (for pagination).
     * Used for "load more" functionality in UI.
     */
    suspend fun findByTaskIdAndSequenceLessThanOrderBySequenceDesc(
        taskId: TaskId,
        sequence: Long,
    ): Flow<ChatMessageDocument>

    /**
     * Search messages by content (case-insensitive).
     * Used by agent search tool to find specific information in history.
     */
    suspend fun findByTaskIdAndContentContainingIgnoreCase(
        taskId: TaskId,
        searchText: String,
    ): Flow<ChatMessageDocument>

    /**
     * Count messages for a task.
     * Used to generate next sequence number when adding new message.
     */
    suspend fun countByTaskId(taskId: TaskId): Long

    /**
     * Count USER messages for a task after a given timestamp.
     * Used to detect inline messages sent while agent was orchestrating.
     */
    suspend fun countByTaskIdAndRoleAndTimestampAfter(
        taskId: TaskId,
        role: MessageRole,
        timestamp: java.time.Instant,
    ): Long

    /**
     * Delete all messages for a task.
     * Used when deleting a task.
     */
    suspend fun deleteByTaskId(taskId: TaskId): Long

    /**
     * Find messages by task and role, ordered by sequence descending.
     * Used by ChatHistoryTools to get user-only or assistant-only messages.
     */
    @Query("{ 'taskId': ?0, 'role': ?1 }")
    suspend fun findByTaskIdAndRole(
        taskId: TaskId,
        role: MessageRole,
    ): Flow<ChatMessageDocument>

    /**
     * Find messages by content search (case-insensitive).
     * Used by ChatHistoryTools for searching conversation history.
     */
    @Query("{ 'taskId': ?0, 'content': { '\$regex': ?1, '\$options': 'i' } }")
    suspend fun findByTaskIdAndContentRegex(
        taskId: TaskId,
        searchText: String,
    ): Flow<ChatMessageDocument>
}
