package com.jervis.service.listener.email

import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.EmailAccountDocument
import com.jervis.service.link.LinkIndexingService
import com.jervis.service.listener.email.imap.ImapClient
import com.jervis.service.listener.email.imap.ImapMessage
import com.jervis.service.listener.email.processor.EmailAttachmentIndexer
import com.jervis.service.listener.email.processor.EmailContentIndexer
import com.jervis.service.listener.email.processor.EmailTaskCreator
import com.jervis.service.listener.email.state.EmailMessageDocument
import com.jervis.service.listener.email.state.EmailMessageStateManager
import kotlinx.coroutines.flow.buffer
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class EmailIndexingOrchestrator(
    private val imapClient: ImapClient,
    private val stateManager: EmailMessageStateManager,
    private val contentIndexer: EmailContentIndexer,
    private val attachmentIndexer: EmailAttachmentIndexer,
    private val linkIndexingService: LinkIndexingService,
    private val taskCreator: EmailTaskCreator,
) {
    suspend fun processAccount(account: EmailAccountDocument) {
        logger.info { "Processing email account ${account.id} (${account.email})" }

        runCatching {
            syncMessageIdsFromImap(account)
            processNewMessages(account)
            logger.info { "Successfully processed account ${account.id}" }
        }.onFailure { e ->
            logger.error(e) { "Failed to process account ${account.id}" }
        }
    }

    private suspend fun syncMessageIdsFromImap(account: EmailAccountDocument) {
        val messageIdsFlow = imapClient.fetchMessageIds(account)
        stateManager.saveNewMessageIds(account.id, messageIdsFlow)
    }

    private suspend fun processNewMessages(account: EmailAccountDocument) {
        stateManager
            .findNewMessages(account.id)
            .buffer(10)
            .collect { messageDoc ->
                processMessage(account, messageDoc)
            }
    }

    private suspend fun processMessage(
        account: EmailAccountDocument,
        messageDoc: EmailMessageDocument,
    ) {
        runCatching {
            logger.info { "Processing message From:${messageDoc.from} Subject:${messageDoc.subject}" }

            val message =
                imapClient.fetchMessage(account, messageDoc.messageId)
                    ?: return logger.warn { "Could not fetch messageId:${messageDoc.messageId}" }

            indexMessage(account, message)
            stateManager.markAsIndexed(messageDoc)

            logger.info { "Successfully indexed messageId:${messageDoc.messageId}" }
        }.onFailure { e ->
            logger.error(e) { "Failed to process messageId:${messageDoc.messageId}" }
        }
    }

    private suspend fun indexMessage(
        account: EmailAccountDocument,
        message: ImapMessage,
    ) {
        contentIndexer.indexEmailContent(
            message = message,
            accountId = account.id,
            clientId = account.clientId,
            projectId = account.projectId,
        )

        attachmentIndexer.indexAttachments(
            message = message,
            accountId = account.id,
            clientId = account.clientId,
            projectId = account.projectId,
        )

        linkIndexingService.indexLinksFromText(
            text = "${message.subject} ${message.content}",
            projectId = account.projectId,
            clientId = account.clientId,
            sourceType = RagSourceType.EMAIL_LINK_CONTENT,
            createdAt = message.receivedAt,
            parentRef = message.messageId,
        )

        taskCreator.createTaskForEmail(
            message = message,
            accountId = account.id,
            clientId = account.clientId,
            projectId = account.projectId,
        )
    }
}
