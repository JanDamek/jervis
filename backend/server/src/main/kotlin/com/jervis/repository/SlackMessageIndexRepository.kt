package com.jervis.repository

import com.jervis.common.types.ConnectionId
import com.jervis.domain.PollingStatusEnum
import com.jervis.entity.slack.SlackMessageIndexDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SlackMessageIndexRepository : CoroutineCrudRepository<SlackMessageIndexDocument, ObjectId> {
    suspend fun existsByConnectionIdAndMessageId(
        connectionId: ConnectionId,
        messageId: String,
    ): Boolean

    fun findByStateOrderByCreatedDateTimeAsc(state: PollingStatusEnum): Flow<SlackMessageIndexDocument>
}
