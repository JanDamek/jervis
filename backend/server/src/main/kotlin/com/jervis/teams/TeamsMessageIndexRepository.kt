package com.jervis.teams

import com.jervis.common.types.ConnectionId
import com.jervis.infrastructure.polling.PollingStatusEnum
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface TeamsMessageIndexRepository : CoroutineCrudRepository<TeamsMessageIndexDocument, ObjectId> {
    suspend fun existsByConnectionIdAndMessageId(
        connectionId: ConnectionId,
        messageId: String,
    ): Boolean

    fun findByStateOrderByCreatedDateTimeAsc(state: PollingStatusEnum): Flow<TeamsMessageIndexDocument>
}
