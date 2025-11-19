package com.jervis.repository.mongo

import com.jervis.domain.MessageChannelEnum
import com.jervis.entity.MessageLinkDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface MessageLinkMongoRepository : CoroutineCrudRepository<MessageLinkDocument, ObjectId> {
    suspend fun findByMessageId(messageId: String): MessageLinkDocument?

    suspend fun findByMessageIdAndThreadId(
        messageId: String,
        threadId: ObjectId,
    ): MessageLinkDocument?

    fun findByThreadIdOrderByTimestampDesc(threadId: ObjectId): Flow<MessageLinkDocument>

    fun findBySenderProfileIdOrderByTimestampDesc(senderProfileId: ObjectId): Flow<MessageLinkDocument>

    fun findByThreadIdAndTimestampBetween(
        threadId: ObjectId,
        startTime: Instant,
        endTime: Instant,
    ): Flow<MessageLinkDocument>

    fun findByChannelAndTimestampAfter(
        channel: MessageChannelEnum,
        timestamp: Instant,
    ): Flow<MessageLinkDocument>

    suspend fun countByThreadId(threadId: ObjectId): Long
}
