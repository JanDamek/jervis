package com.jervis.repository

import com.jervis.entity.ChatSummaryDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * ChatSummaryRepository — access to compressed chat summary blocks.
 *
 * Chat context and compression are handled by Python ChatContextAssembler
 * (direct MongoDB access). Kotlin uses this for UI display.
 */
@Repository
interface ChatSummaryRepository : CoroutineCrudRepository<ChatSummaryDocument, ObjectId> {

    /**
     * Load all summaries for a conversation, ordered by sequenceEnd ascending.
     * Used to build rolling context for the orchestrator.
     */
    suspend fun findByConversationIdOrderBySequenceEndAsc(conversationId: ObjectId): Flow<ChatSummaryDocument>

    /**
     * Find the latest summary for a conversation (highest sequenceEnd).
     * Used to determine where compression left off.
     */
    suspend fun findFirstByConversationIdOrderBySequenceEndDesc(conversationId: ObjectId): ChatSummaryDocument?

    /**
     * Delete all summaries for a conversation.
     * Used when deleting a task/session.
     */
    suspend fun deleteByConversationId(conversationId: ObjectId): Long
}
