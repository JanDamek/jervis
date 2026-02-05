package com.jervis.repository

import com.jervis.common.types.ConnectionId
import com.jervis.domain.PollingStatusEnum
import com.jervis.entity.email.EmailMessageIndexDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for email message index documents.
 *
 * Note: EmailMessageIndexDocument is a sealed class with polymorphic storage.
 * Spring Data auto-generates queries based on _class field for sealed classes.
 * For queries on abstract properties (like 'state'), we need explicit @Query annotations.
 */
@Repository
interface EmailMessageIndexRepository : CoroutineCrudRepository<EmailMessageIndexDocument, ObjectId> {
    /**
     * Find message by connection and UID (for duplicate detection).
     * Works with sealed classes because connectionId and messageUid are in all subclasses.
     */
    suspend fun existsByConnectionIdAndMessageUid(
        connectionId: ConnectionId,
        messageUid: String,
    ): Boolean

    /**
     * Find all messages by state, ordered by received date.
     * Uses explicit query because 'state' is abstract property in sealed class.
     */
    fun findByStateOrderByReceivedDateAsc(state: PollingStatusEnum): Flow<EmailMessageIndexDocument>
}
