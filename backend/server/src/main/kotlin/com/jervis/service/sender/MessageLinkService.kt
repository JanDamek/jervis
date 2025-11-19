package com.jervis.service.sender

import com.jervis.domain.MessageChannelEnum
import com.jervis.domain.sender.MessageLink
import com.jervis.mapper.toDomain
import com.jervis.mapper.toEntity
import com.jervis.repository.mongo.MessageLinkMongoRepository
import com.jervis.service.listener.email.imap.ImapMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class MessageLinkService(
    private val repository: MessageLinkMongoRepository,
) {
    suspend fun findById(id: ObjectId): MessageLink? = repository.findById(id)?.let { it.toDomain() }

    suspend fun findByMessageId(messageId: String): MessageLink? = repository.findByMessageId(messageId)?.let { it.toDomain() }

    fun findByThreadId(
        threadId: ObjectId,
        limit: Int = 50,
    ): Flow<MessageLink> =
        repository
            .findByThreadIdOrderByTimestampDesc(threadId)
            .take(limit)
            .map { it.toDomain() }

    fun findBySenderProfileId(
        senderProfileId: ObjectId,
        limit: Int = 50,
    ): Flow<MessageLink> =
        repository
            .findBySenderProfileIdOrderByTimestampDesc(senderProfileId)
            .take(limit)
            .map { it.toDomain() }

    suspend fun createLink(
        messageId: String,
        channel: MessageChannelEnum,
        threadId: ObjectId,
        senderProfileId: ObjectId,
        subject: String?,
        content: String?,
        timestamp: Instant,
        hasAttachments: Boolean,
        ragDocumentId: String?,
    ): MessageLink {
        // Scope deduplication by thread to avoid cross-client/global collisions on messageId
        repository.findByMessageIdAndThreadId(messageId, threadId)?.let { existing ->
            logger.debug { "Message link already exists in thread: $messageId / $threadId" }
            return existing.toDomain()
        }

        logger.info { "Creating message link: $messageId in thread $threadId" }

        val snippet = content?.take(200)

        val newLink =
            MessageLink(
                id = ObjectId(),
                messageId = messageId,
                channel = channel,
                threadId = threadId,
                senderProfileId = senderProfileId,
                subject = subject,
                snippet = snippet,
                timestamp = timestamp,
                hasAttachments = hasAttachments,
                ragDocumentId = ragDocumentId,
            )

        val entity = newLink.toEntity()
        try {
            val saved = repository.save(entity)
            return saved.toDomain()
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("E11000") || msg.contains("duplicate key", ignoreCase = true)) {
                logger.warn { "Duplicate message link detected during create (messageId=$messageId, thread=$threadId). Retrying fetch." }
                val existing = repository.findByMessageIdAndThreadId(messageId, threadId)
                if (existing != null) return existing.toDomain()
            }
            throw e
        }
    }

    suspend fun createLinkFromEmail(
        email: ImapMessage,
        threadId: ObjectId,
        senderProfileId: ObjectId,
        ragDocumentId: String?,
    ): MessageLink =
        createLink(
            messageId = email.messageId,
            channel = MessageChannelEnum.EMAIL,
            threadId = threadId,
            senderProfileId = senderProfileId,
            subject = email.subject,
            content = email.content,
            timestamp = email.receivedAt,
            hasAttachments = email.attachments.isNotEmpty(),
            ragDocumentId = ragDocumentId,
        )

    suspend fun countByThreadId(threadId: ObjectId): Long = repository.countByThreadId(threadId)
}
