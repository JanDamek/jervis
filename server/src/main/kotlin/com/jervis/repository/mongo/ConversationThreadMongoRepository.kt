package com.jervis.repository.mongo

import com.jervis.entity.ConversationThreadDocument
import com.jervis.entity.ThreadStatus
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ConversationThreadMongoRepository : CoroutineCrudRepository<ConversationThreadDocument, ObjectId> {
    suspend fun findByThreadId(threadId: String): ConversationThreadDocument?

    fun findBySenderProfileIdsContaining(senderProfileId: ObjectId): Flow<ConversationThreadDocument>

    fun findByClientIdAndProjectId(
        clientId: ObjectId,
        projectId: ObjectId?,
    ): Flow<ConversationThreadDocument>

    fun findByStatusAndLastMessageAtBefore(
        status: ThreadStatus,
        timestamp: Instant,
    ): Flow<ConversationThreadDocument>

    fun findByRequiresResponseTrueAndResponseDeadlineBefore(deadline: Instant): Flow<ConversationThreadDocument>

    suspend fun countByClientIdAndStatus(
        clientId: ObjectId,
        status: ThreadStatus,
    ): Long
}
