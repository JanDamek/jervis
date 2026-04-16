package com.jervis.teams

import com.jervis.common.types.ConnectionId
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface O365MessageLedgerRepository : CoroutineCrudRepository<O365MessageLedgerDocument, ObjectId> {
    fun findByConnectionId(connectionId: ConnectionId): Flow<O365MessageLedgerDocument>

    fun findByConnectionIdAndUnreadCountGreaterThan(
        connectionId: ConnectionId,
        threshold: Int,
    ): Flow<O365MessageLedgerDocument>

    suspend fun findByConnectionIdAndChatId(
        connectionId: ConnectionId,
        chatId: String,
    ): O365MessageLedgerDocument?
}
