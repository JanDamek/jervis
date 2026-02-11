package com.jervis.repository

import com.jervis.common.types.TaskId
import com.jervis.entity.ChatSummaryDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * ChatSummaryRepository — access to compressed chat summary blocks.
 *
 * Used by ChatHistoryService to load existing summaries for orchestrator context
 * and to store new summaries after compression.
 */
@Repository
interface ChatSummaryRepository : CoroutineCrudRepository<ChatSummaryDocument, ObjectId> {

    /**
     * Load all summaries for a task, ordered by sequenceEnd ascending.
     * Used to build rolling context for the orchestrator.
     */
    suspend fun findByTaskIdOrderBySequenceEndAsc(taskId: TaskId): Flow<ChatSummaryDocument>

    /**
     * Find the latest summary for a task (highest sequenceEnd).
     * Used to determine where compression left off.
     */
    suspend fun findFirstByTaskIdOrderBySequenceEndDesc(taskId: TaskId): ChatSummaryDocument?

    /**
     * Delete all summaries for a task.
     * Used when deleting a task.
     */
    suspend fun deleteByTaskId(taskId: TaskId): Long
}
