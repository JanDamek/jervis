package com.jervis.repository

import com.jervis.entity.email.EmailMessageIndexDocument
import com.jervis.types.ConnectionId
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
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
interface EmailMessageIndexMongoRepository : CoroutineCrudRepository<EmailMessageIndexDocument, ObjectId> {
    /**
     * Find message by connection and UID (for duplicate detection).
     * Works with sealed classes because connectionId and messageUid are in all subclasses.
     */
    @Query("{ 'connectionId': ?0, 'messageUid': ?1 }")
    suspend fun findByConnectionIdAndMessageUid(
        connectionId: ConnectionId,
        messageUid: String,
    ): EmailMessageIndexDocument?

    /**
     * Find all messages by state, ordered by received date.
     * Uses explicit query because 'state' is abstract property in sealed class.
     */
    @Query(value = "{ 'state': ?0 }", sort = "{ 'receivedDate': 1 }")
    fun findByStateOrderByReceivedDateAsc(state: String): Flow<EmailMessageIndexDocument>
}
