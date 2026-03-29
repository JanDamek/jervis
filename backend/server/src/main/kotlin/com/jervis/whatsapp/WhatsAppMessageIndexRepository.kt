package com.jervis.whatsapp

import com.jervis.common.types.ConnectionId
import com.jervis.infrastructure.polling.PollingStatusEnum
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface WhatsAppMessageIndexRepository : CoroutineCrudRepository<WhatsAppMessageIndexDocument, ObjectId> {
    suspend fun existsByConnectionIdAndMessageId(connectionId: ConnectionId, messageId: String): Boolean
    fun findByConnectionIdAndState(connectionId: ConnectionId, state: PollingStatusEnum): Flow<WhatsAppMessageIndexDocument>
    fun findByStateOrderByCreatedDateTimeAsc(state: PollingStatusEnum): Flow<WhatsAppMessageIndexDocument>
}
