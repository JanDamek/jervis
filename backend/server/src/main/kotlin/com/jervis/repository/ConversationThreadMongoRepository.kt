package com.jervis.repository

import com.jervis.entity.ConversationThreadDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ConversationThreadMongoRepository : CoroutineCrudRepository<ConversationThreadDocument, ObjectId> {
    suspend fun findByThreadIdAndClientIdAndProjectId(
        threadId: String,
        clientId: ObjectId,
        projectId: ObjectId?,
    ): ConversationThreadDocument?

    fun findBySenderProfileIdsContaining(senderProfileId: ObjectId): Flow<ConversationThreadDocument>

    fun findByClientIdAndProjectId(
        clientId: ObjectId,
        projectId: ObjectId?,
    ): Flow<ConversationThreadDocument>

    fun findByRequiresResponseTrueAndResponseDeadlineBefore(deadline: Instant): Flow<ConversationThreadDocument>
}
