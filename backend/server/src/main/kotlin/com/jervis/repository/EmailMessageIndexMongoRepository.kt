package com.jervis.repository

import com.jervis.entity.email.EmailMessageIndexDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for email message index documents.
 */
@Repository
interface EmailMessageIndexMongoRepository : CoroutineCrudRepository<EmailMessageIndexDocument, ObjectId> {
    /**
     * Find message by connection and UID (for duplicate detection).
     */
    suspend fun findByConnectionIdAndMessageUid(
        connectionId: ObjectId,
        messageUid: String,
    ): EmailMessageIndexDocument?

    /**
     * Find all NEW messages that need indexing.
     */
    fun findByStateOrderByReceivedDateAsc(state: String): Flow<EmailMessageIndexDocument>
}
