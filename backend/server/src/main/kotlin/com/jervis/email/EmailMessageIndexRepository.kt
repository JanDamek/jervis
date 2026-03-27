package com.jervis.email

import com.jervis.common.types.ConnectionId
import com.jervis.infrastructure.polling.PollingStatusEnum
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface EmailMessageIndexRepository : CoroutineCrudRepository<EmailMessageIndexDocument, ObjectId> {
    suspend fun existsByConnectionIdAndMessageUid(
        connectionId: ConnectionId,
        messageUid: String,
    ): Boolean

    fun findByStateOrderByReceivedDateAsc(state: PollingStatusEnum): Flow<EmailMessageIndexDocument>

    fun findByThreadIdOrderBySentDateAsc(threadId: String): Flow<EmailMessageIndexDocument>

    suspend fun existsByThreadIdAndDirection(threadId: String, direction: EmailDirection): Boolean
}
