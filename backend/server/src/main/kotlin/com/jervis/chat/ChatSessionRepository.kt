package com.jervis.chat

import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * ChatSessionRepository — access to chat sessions.
 */
@Repository
interface ChatSessionRepository : CoroutineCrudRepository<ChatSessionDocument, ObjectId> {

    /**
     * List sessions for a user, ordered by last message time.
     */
    fun findByUserIdAndArchivedOrderByLastMessageAtDesc(
        userId: String,
        archived: Boolean,
    ): Flow<ChatSessionDocument>

    /**
     * Find the most recent active session for a user.
     */
    suspend fun findFirstByUserIdAndArchivedOrderByLastMessageAtDesc(
        userId: String,
        archived: Boolean,
    ): ChatSessionDocument?
}
