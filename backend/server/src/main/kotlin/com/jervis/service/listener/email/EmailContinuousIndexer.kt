package com.jervis.service.listener.email

import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.EmailAccountDocument
import com.jervis.service.background.TaskQualificationService
import com.jervis.service.indexing.status.IndexingStatusRegistry
import com.jervis.service.link.LinkIndexingService
import com.jervis.service.listener.email.imap.ImapClient
import com.jervis.service.listener.email.imap.ImapMessage
import com.jervis.service.listener.email.processor.EmailAttachmentIndexer
import com.jervis.service.listener.email.processor.EmailContentIndexer
import com.jervis.service.listener.email.state.EmailMessageStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    private val flowProps: com.jervis.configuration.properties.IndexingFlowProperties,
    private val indexingRegistry: IndexingStatusRegistry,
) : com.jervis.service.indexing.AbstractContinuousIndexer<EmailAccountDocument, com.jervis.service.listener.email.state.EmailMessageDocument>() {
    override val indexerName: String = "EmailContinuousIndexer"
    override val bufferSize: Int get() = flowProps.bufferSize

    override fun newItemsFlow(account: EmailAccountDocument) = stateManager.continuousNewMessages(account.id)

    override fun itemLogLabel(item: com.jervis.service.listener.email.state.EmailMessageDocument) =
        "UID:${item.uid} msgId:${item.messageId} subject:${item.subject}"

    override suspend fun fetchContentIO(
        account: EmailAccountDocument,
        item: com.jervis.service.listener.email.state.EmailMessageDocument,
    ): ImapMessage? {
        // Claim the item by marking it INDEXING before any remote IO
        stateManager.markAsIndexing(item)
        val uid = item.uid
        if (uid == null) return null
        return imapClient.fetchMessage(account, uid)
    }

    override suspend fun processAndIndex(
        account: EmailAccountDocument,
        item: com.jervis.service.listener.email.state.EmailMessageDocument,
        content: Any,
    ): IndexingResult {
        val message = content as ImapMessage

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

        val success = ragDocumentId != null
        // Report progress to indexing registry under tool key "email"
        kotlin.runCatching {
            val toolKey = "email"
            // ensure running state started once per process lifecycle
            indexingRegistry.ensureTool(toolKey, displayName = "Email Indexing")
            if (success) indexingRegistry.progress(toolKey, processedInc = 1, message = "Indexed email ${message.messageId}")
        }
        return IndexingResult(
            success = success,
            plainText = message.content,
            ragDocumentId = ragDocumentId,
        )
    }

    override fun shouldCreateTask(
        account: EmailAccountDocument,
        item: com.jervis.service.listener.email.state.EmailMessageDocument,
        content: Any,
        result: IndexingResult,
    ): Boolean = true

    override suspend fun createTask(
        account: EmailAccountDocument,
        item: com.jervis.service.listener.email.state.EmailMessageDocument,
        content: Any,
        result: IndexingResult,
    ) {
        val message = content as ImapMessage
        taskQualificationService.qualifyEmailEnriched(
            email = message,
            accountId = account.id,
            clientId = account.clientId,
            projectId = account.projectId,
            ragDocumentId = result.ragDocumentId,
        )
    }

    override suspend fun markAsIndexed(
        account: EmailAccountDocument,
        item: com.jervis.service.listener.email.state.EmailMessageDocument,
    ) {
        kotlin
            .runCatching { stateManager.markAsIndexed(item) }
            .onFailure { e ->
                logger.warn(e) { "markAsIndexed failed for messageId:${item.messageId}, trying by messageId" }
                kotlin
                    .runCatching { stateManager.markMessageIdAsIndexed(account.id, item.messageId) }
                    .onFailure { e2 -> logger.error(e2) { "Fallback mark by messageId also failed for messageId:${item.messageId}" } }
            }
        logger.info { "Successfully indexed messageId:${item.messageId}" }
    }

    override suspend fun markAsFailed(
        account: EmailAccountDocument,
        item: com.jervis.service.listener.email.state.EmailMessageDocument,
        reason: String,
    ) {
        stateManager.markAsFailed(item)
        kotlin.runCatching {
            val toolKey = "email"
            indexingRegistry.ensureTool(toolKey, displayName = "Email Indexing")
            indexingRegistry.error(toolKey, "Failed to index messageId=${item.messageId}: $reason")
        }
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
            kotlin.runCatching {
                indexingRegistry.start(
                    "email",
                    displayName = "Email Indexing",
                    message = "Starting continuous email indexing for account ${account.id}",
                )
            }
            runCatching { startContinuousIndexing(account) }
                .onFailure { e -> logger.error(e) { "Continuous indexer crashed for account ${account.id}" } }
                .also {
                    kotlin.runCatching {
                        indexingRegistry.finish(
                            "email",
                            message = "Email indexer stopped for account ${account.id}",
                        )
                    }
                }
        }
    }
}
