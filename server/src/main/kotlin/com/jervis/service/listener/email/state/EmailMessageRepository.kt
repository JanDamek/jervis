package com.jervis.service.listener.email.state

import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface EmailMessageRepository : CoroutineCrudRepository<EmailMessageDocument, ObjectId> {
    fun findByAccountIdAndStateOrderByReceivedAtAsc(
        accountId: ObjectId,
        state: EmailMessageState,
    ): Flow<EmailMessageDocument>

    fun findByAccountIdAndMessageId(
        accountId: ObjectId,
        messageId: String,
    ): EmailMessageDocument?

    fun findAllByAccountIdAndMessageIdIn(
        accountId: ObjectId,
        messageIds: Collection<String>,
    ): Flow<EmailMessageDocument>
}
