package com.jervis.service.listener.email.state

import com.jervis.service.listener.email.imap.ImapMessageId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
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

        val imapMessages = mutableListOf<ImapMessageId>()
        messageIds.collect { imapMessages.add(it) }

        logger.info { "Found ${imapMessages.size} messages in IMAP for account $accountId" }

        if (imapMessages.isEmpty()) {
            logger.info { "No messages to process for account $accountId" }
            return
        }

        val existingMessageIds = loadExistingMessageIds(accountId)
        logger.info { "Found ${existingMessageIds.size} existing messages in DB for account $accountId" }

        val newMessages = imapMessages.filterNot { existingMessageIds.contains(it.messageId) }
        logger.info { "Identified ${newMessages.size} new messages to save for account $accountId" }

        newMessages.forEach { saveNewMessage(accountId, it) }
        logger.info { "Batch sync completed for account $accountId" }
    }

    private suspend fun loadExistingMessageIds(accountId: ObjectId): Set<String> {
        val existingMessages = mutableListOf<EmailMessageDocument>()

        emailMessageRepository
            .findByAccountId(accountId)
            .asFlow()
            .collect { existingMessages.add(it) }

        return existingMessages.map { it.messageId }.toSet()
    }

    private suspend fun saveNewMessage(
        accountId: ObjectId,
        imapMessageId: ImapMessageId,
    ) {
        val newMessage =
            EmailMessageDocument(
                accountId = accountId,
                messageId = imapMessageId.messageId,
                state = EmailMessageState.NEW,
                subject = imapMessageId.subject,
                from = imapMessageId.from,
                receivedAt = imapMessageId.receivedAt,
            )
        emailMessageRepository.save(newMessage).awaitSingleOrNull()
        logger.debug { "Saved new message for ${imapMessageId.from} of messageId:${imapMessageId.messageId} with state NEW" }
    }

    fun findNewMessages(accountId: ObjectId): Flow<EmailMessageDocument> =
        emailMessageRepository
            .findByAccountIdAndStateOrderByReceivedAtAsc(accountId, EmailMessageState.NEW)
            .asFlow()

    suspend fun markAsIndexed(messageDocument: EmailMessageDocument) {
        val updated = messageDocument.copy(state = EmailMessageState.INDEXED)
        emailMessageRepository.save(updated).awaitSingleOrNull()
        logger.debug { "Marked messageId ${messageDocument.messageId} as INDEXED" }
    }
}
