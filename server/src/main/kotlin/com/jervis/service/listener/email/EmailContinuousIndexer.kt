package com.jervis.service.listener.email

import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.EmailAccountDocument
import com.jervis.service.background.TaskQualificationService
import com.jervis.service.link.LinkIndexingService
import com.jervis.service.listener.email.imap.ImapClient
import com.jervis.service.listener.email.imap.ImapMessage
import com.jervis.service.listener.email.processor.EmailAttachmentIndexer
import com.jervis.service.listener.email.processor.EmailContentIndexer
import com.jervis.service.listener.email.state.EmailMessageStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer that runs as a background Flow.
 * Polls NEW messages from DB, processes them, and marks as INDEXED.
 * Never stops - uses polling with 30s delay when queue is empty.
 */
@Service
class EmailContinuousIndexer(
    private val imapClient: ImapClient,
    private val stateManager: EmailMessageStateManager,
    private val contentIndexer: EmailContentIndexer,
    private val attachmentIndexer: EmailAttachmentIndexer,
    private val linkIndexingService: LinkIndexingService,
    private val taskQualificationService: TaskQualificationService,
) {
    /**
     * Starts continuous indexing for an account. This never returns.
     * Should be launched in a separate coroutine scope.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun startContinuousIndexing(account: EmailAccountDocument) {
        logger.info { "Starting continuous indexer for account ${account.id} (${account.email})" }

        stateManager
            .continuousNewMessages(account.id)
            .buffer(128) // Back-pressure relief between DB and indexing
            .flatMapMerge(concurrency = 5) { messageDoc ->
                flow {
                    logger.info { "Indexing message UID:${messageDoc.uid} From:${messageDoc.from} Subject:${messageDoc.subject}" }

                    val uid = messageDoc.uid
                    if (uid == null) {
                        logger.warn { "Message has no UID messageId:${messageDoc.messageId}, marking as FAILED" }
                        stateManager.markAsFailed(messageDoc)
                        return@flow
                    }

                    // IMAP operations should run on IO dispatcher
                    val message =
                        withContext(Dispatchers.IO) {
                            imapClient.fetchMessage(account, uid)
                        }

                    if (message == null) {
                        logger.warn { "Could not fetch UID:$uid messageId:${messageDoc.messageId}, marking as FAILED" }
                        stateManager.markAsFailed(messageDoc)
                        return@flow
                    }

                    indexMessage(account, message)
                    emit(messageDoc)
                }.catch { e ->
                    logger.error(e) { "Indexing failed for UID:${messageDoc.uid} messageId:${messageDoc.messageId}, marking as FAILED" }
                    stateManager.markAsFailed(messageDoc)
                }
            }.onEach { messageDoc ->
                stateManager.markAsIndexed(messageDoc)
                logger.info { "Successfully indexed messageId:${messageDoc.messageId}" }
            }.collect { }
    }

    private suspend fun indexMessage(
        account: EmailAccountDocument,
        message: ImapMessage,
    ) {
        val ragDocumentId =
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

        taskQualificationService.qualifyEmailEnriched(
            email = message,
            accountId = account.id,
            clientId = account.clientId,
            projectId = account.projectId,
            ragDocumentId = ragDocumentId,
        )
    }

    /**
     * Helper to launch continuous indexing in a scope.
     * Use this from application startup or account activation.
     */
    fun launchContinuousIndexing(
        account: EmailAccountDocument,
        scope: CoroutineScope,
    ) {
        scope.launch {
            runCatching {
                startContinuousIndexing(account)
            }.onFailure { e ->
                logger.error(e) { "Continuous indexer crashed for account ${account.id}" }
            }
        }
    }
}
