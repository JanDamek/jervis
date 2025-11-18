package com.jervis.service.listener.email.state

import com.jervis.service.listener.email.imap.ImapMessageId
import com.jervis.util.chunked
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.firstOrNull
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
    /** Mark given message as INDEXING. Used to claim work before IMAP fetch. */
    suspend fun markAsIndexing(messageDocument: EmailMessageDocument) {
        if (messageDocument.state == EmailMessageState.INDEXING) return
        val updated = messageDocument.copy(state = EmailMessageState.INDEXING)
        emailMessageRepository.save(updated)
        logger.debug { "Marked messageId ${messageDocument.messageId} as INDEXING" }
    }

    suspend fun saveNewMessageIds(
        accountId: ObjectId,
        messageIds: Flow<ImapMessageId>,
    ) {
        logger.info { "Starting batch message ID sync for account $accountId" }
        var totalProcessed = 0
        var totalSaved = 0
        var totalDuplicatesInFlow = 0

        messageIds
            .buffer(200)
            .chunked(100)
            .collect { batch ->
                // CRITICAL FIX: Deduplicate within batch FIRST
                // IMAP can return same Message-ID with different UIDs (moved between folders)
                val uniqueBatch = batch
                    .groupBy { it.messageId }
                    .mapValues { it.value.first() } // Keep first occurrence of each messageId
                    .values
                    .toList()

                val duplicatesInBatch = batch.size - uniqueBatch.size
                if (duplicatesInBatch > 0) {
                    logger.debug { "Found $duplicatesInBatch duplicate Message-IDs within IMAP batch (same email in multiple folders)" }
                    totalDuplicatesInFlow += duplicatesInBatch
                }

                totalProcessed += uniqueBatch.size

                val batchMessageIds = uniqueBatch.map { it.messageId }

                // Find existing in single query (batch lookup, avoiding N+1)
                val existingSet = mutableSetOf<String>()
                emailMessageRepository
                    .findAllByAccountIdAndMessageIdIn(accountId, batchMessageIds)
                    .collect { existingSet.add(it.messageId) }

                // Filter only new messages
                val newDocs =
                    uniqueBatch
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
                    var savedInBatch = 0
                    newDocs.forEach { doc ->
                        runCatching {
                            emailMessageRepository.save(doc)
                            savedInBatch++
                        }.onFailure { e ->
                            // Check for MongoDB duplicate key error (E11000)
                            if (e is com.mongodb.MongoWriteException && e.error.code == 11000) {
                                logger.debug { "Skipping duplicate email messageId=${doc.messageId} for account=${doc.accountId.toHexString()}" }
                            } else {
                                logger.error(e) { "Failed to save email messageId=${doc.messageId}: ${e.message}" }
                                throw e
                            }
                        }
                    }
                    totalSaved += savedInBatch
                    logger.info { "Saved $savedInBatch new messages (total: $totalProcessed processed, $totalSaved saved)" }
                }
            }

        logger.info { "Batch sync completed for account $accountId: saved $totalSaved new messages (processed $totalProcessed unique, skipped $totalDuplicatesInFlow IMAP duplicates)" }
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
        val existing =
            emailMessageRepository
                .findAllByAccountIdAndMessageIdIn(accountId, listOf(messageId))
                .firstOrNull()
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

    /** On app start, reset any messages stuck in INDEXING back to NEW (previous run didn't complete). */
    suspend fun resetDanglingIndexingToNewOnStartup(): Int {
        var reset = 0
        emailMessageRepository
            .findByState(EmailMessageState.INDEXING)
            .collect { doc ->
                emailMessageRepository.save(doc.copy(state = EmailMessageState.NEW))
                reset++
            }
        if (reset > 0) {
            logger.warn { "Startup recovery: reset $reset email messages from INDEXING to NEW" }
        } else {
            logger.info { "Startup recovery: no dangling INDEXING email messages found" }
        }
        return reset
    }
}
