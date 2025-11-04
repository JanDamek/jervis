package com.jervis.service.listener.email.state

import com.jervis.service.listener.email.imap.ImapMessageId
import com.jervis.util.chunked
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class EmailMessageStateManager(
    private val emailMessageRepository: EmailMessageRepository,
) {
    suspend fun saveNewMessageIds(
        accountId: ObjectId,
        messageIds: Flow<ImapMessageId>,
    ) {
        logger.info { "Starting batch message ID sync for account $accountId" }
        var totalProcessed = 0
        var totalSaved = 0

        messageIds
            .buffer(200)
            .chunked(100)
            .collect { batch ->
                totalProcessed += batch.size

                val batchMessageIds = batch.map { it.messageId }

                // Find existing in single query (batch lookup, avoiding N+1)
                val existingSet = mutableSetOf<String>()
                emailMessageRepository
                    .findAllByAccountIdAndMessageIdIn(accountId, batchMessageIds)
                    .collect { existingSet.add(it.messageId) }

                // Filter only new messages
                val newDocs =
                    batch
                        .filter { it.messageId !in existingSet }
                        .map {
                            EmailMessageDocument(
                                accountId = accountId,
                                messageId = it.messageId,
                                uid = it.uid,
                                state = EmailMessageState.NEW,
                                subject = it.subject,
                                from = it.from,
                                receivedAt = it.receivedAt,
                            )
                        }

                if (newDocs.isNotEmpty()) {
                    newDocs.forEach { emailMessageRepository.save(it) }
                    totalSaved += newDocs.size
                    logger.info { "Saved ${newDocs.size} new messages (total: $totalProcessed processed, $totalSaved saved)" }
                }
            }

        logger.info { "Batch sync completed for account $accountId: saved $totalSaved new messages (processed $totalProcessed total)" }
    }

    fun findNewMessages(accountId: ObjectId): Flow<EmailMessageDocument> =
        emailMessageRepository
            .findByAccountIdAndStateOrderByReceivedAtAsc(accountId, EmailMessageState.NEW)

    /**
     * Continuous polling Flow that never ends.
     * Reads NEW messages until exhausted, then waits 30s and repeats.
     */
    fun continuousNewMessages(accountId: ObjectId): Flow<EmailMessageDocument> =
        flow {
            while (currentCoroutineContext().isActive) {
                var foundAny = false

                findNewMessages(accountId)
                    .onEach { foundAny = true }
                    .collect { emit(it) }

                if (!foundAny) {
                    delay(30_000) // No new messages, wait 30s
                } else {
                    logger.debug { "Batch of NEW messages processed, checking for more immediately" }
                }
            }
        }

    suspend fun markAsIndexed(messageDocument: EmailMessageDocument) {
        val updated = messageDocument.copy(state = EmailMessageState.INDEXED)
        emailMessageRepository.save(updated)
        logger.debug { "Marked messageId ${messageDocument.messageId} as INDEXED" }
    }

    suspend fun markMessageIdAsIndexed(
        accountId: ObjectId,
        messageId: String,
    ): Boolean {
        val existing = emailMessageRepository.findByAccountIdAndMessageId(accountId, messageId)
        if (existing == null) {
            logger.warn { "markMessageIdAsIndexed: message not found for account=$accountId messageId=$messageId" }
            return false
        }
        if (existing.state == EmailMessageState.INDEXED) {
            return true
        }
        val updated = existing.copy(state = EmailMessageState.INDEXED)
        emailMessageRepository.save(updated)
        logger.info { "Marked messageId $messageId as INDEXED (by messageId lookup)" }
        return true
    }

    suspend fun markAsFailed(messageDocument: EmailMessageDocument) {
        val updated = messageDocument.copy(state = EmailMessageState.FAILED)
        emailMessageRepository.save(updated)
        logger.warn { "Marked messageId ${messageDocument.messageId} as FAILED (could not fetch from IMAP)" }
    }
}
