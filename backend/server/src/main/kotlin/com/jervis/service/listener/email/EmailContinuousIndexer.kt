package com.jervis.service.listener.email

import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.EmailAccountDocument
import com.jervis.repository.EmailAccountMongoRepository
import com.jervis.service.background.TaskQualificationService
import com.jervis.service.indexing.AbstractContinuousIndexer
import com.jervis.service.indexing.status.IndexingStatusRegistry
import com.jervis.service.link.LinkIndexingService
import com.jervis.service.listener.email.imap.ImapClient
import com.jervis.service.listener.email.imap.ImapMessage
import com.jervis.service.listener.email.processor.EmailAttachmentIndexer
import com.jervis.service.listener.email.processor.EmailContentIndexer
import com.jervis.service.listener.email.state.EmailMessageDocument
import com.jervis.service.listener.email.state.EmailMessageStateManager
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer that processes NEW email messages from MongoDB.
 * Reads messages saved by EmailContinuousPoller, indexes them to RAG,
 * creates pending tasks for qualification, and marks as INDEXED.
 */
@Service
@Order(10) // Start after WeaviateSchemaInitializer
class EmailContinuousIndexer(
    private val emailAccountRepository: EmailAccountMongoRepository,
    private val imapClient: ImapClient,
    private val stateManager: EmailMessageStateManager,
    private val contentIndexer: EmailContentIndexer,
    private val attachmentIndexer: EmailAttachmentIndexer,
    private val linkIndexingService: LinkIndexingService,
    private val taskQualificationService: TaskQualificationService,
    private val flowProps: com.jervis.configuration.properties.IndexingFlowProperties,
    private val indexingRegistry: IndexingStatusRegistry,
) : AbstractContinuousIndexer<EmailAccountDocument, EmailMessageDocument>() {
    override val indexerName: String = "EmailContinuousIndexer"
    override val bufferSize: Int get() = flowProps.bufferSize

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting $indexerName for all email accounts..." }
        scope.launch {
            // Start indexer for each email account
            val accounts = emailAccountRepository.findAll().toList()
            accounts.forEach { account ->
                launch {
                    logger.info { "Starting continuous indexing for account: ${account.email}" }
                    startContinuousIndexing(account)
                }
            }
        }
    }

    override fun newItemsFlow(account: EmailAccountDocument) = stateManager.continuousNewMessages(account.id)

    override fun itemLogLabel(item: EmailMessageDocument) =
        "UID:${item.uid} from:${item.from ?: "<unknown>"} subject:${item.subject ?: "<no subject>"}"

    override suspend fun fetchContentIO(
        account: EmailAccountDocument,
        item: EmailMessageDocument,
    ): ImapMessage? {
        // Claim the item by marking it INDEXING before any remote IO
        stateManager.markAsIndexing(item)
        val uid = item.uid
        if (uid == null) return null
        return imapClient.fetchMessage(account, uid)
    }

    override suspend fun processAndIndex(
        account: EmailAccountDocument,
        item: EmailMessageDocument,
        content: Any,
    ): IndexingResult {
        val message = content as ImapMessage

        // Runtime guard: skip heavy processing if this Message-ID is already INDEXED for the account
        if (stateManager.isAlreadyIndexed(account.id, message.messageId)) {
            kotlin.runCatching {
                val toolKey = "email"
                indexingRegistry.ensureTool(toolKey, displayName = "Email Indexing")
                indexingRegistry.progress(
                    toolKey,
                    processedInc = 1,
                    message = "Skipped duplicate email from=${message.from} subject=${message.subject}",
                )
            }
            logger.info { "Duplicate email detected, skipping indexing for from=${message.from} subject=${message.subject}" }
            return IndexingResult(
                success = true,
                plainText = null,
                ragDocumentId = null, // Important: no task will be created when null (see shouldCreateTask)
            )
        }

        // Compute canonical source ID for the email thread (normalized subject within this account)
        val normalizedSubject = normalizeSubject(message.subject)
        val subjectHash = sha256(normalizedSubject.lowercase())
        val canonicalSourceId = "email-thread://${account.id.toHexString()}/$subjectHash"

        val ragDocumentId =
            contentIndexer.indexEmailContent(
                message = message,
                accountId = account.id,
                clientId = account.clientId,
                projectId = account.projectId,
                canonicalSourceId = canonicalSourceId,
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
            parentRef = message.messageId,
        )

        kotlin.runCatching {
            val toolKey = "email"
            indexingRegistry.ensureTool(toolKey, displayName = "Email Indexing")
            indexingRegistry.progress(
                toolKey,
                processedInc = 1,
                message = "Indexed email from=${message.from} subject=${message.subject}",
            )
        }
        return IndexingResult(
            success = true,
            plainText = message.content,
            ragDocumentId = ragDocumentId,
        )
    }

    override fun shouldCreateTask(
        account: EmailAccountDocument,
        item: com.jervis.service.listener.email.state.EmailMessageDocument,
        content: Any,
        result: IndexingResult,
    ): Boolean {
        // Do not create a task for duplicates (ragDocumentId==null in that case)
        return result.ragDocumentId != null
    }

    override suspend fun createTask(
        account: EmailAccountDocument,
        item: EmailMessageDocument,
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
                logger.warn(e) {
                    "markAsIndexed failed for email from=${item.from ?: "<unknown>"} subject=${item.subject ?: "<no subject>"}, trying by messageId"
                }
                kotlin
                    .runCatching { stateManager.markMessageIdAsIndexed(account.id, item.messageId) }
                    .onFailure { e2 ->
                        logger.error(e2) {
                            "Fallback mark by messageId also failed for email from=${item.from ?: "<unknown>"} subject=${item.subject ?: "<no subject>"}"
                        }
                    }
            }
        logger.info { "Successfully indexed email from=${item.from ?: "<unknown>"} subject=${item.subject ?: "<no subject>"}" }
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
            indexingRegistry.error(
                toolKey,
                "Failed to index email from=${item.from ?: "<unknown>"} subject=${item.subject ?: "<no subject>"}: $reason",
            )
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

    private fun normalizeSubject(subject: String?): String =
        subject
            ?.replace(Regex("^(Re:|Fwd:|Fw:)\\s*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: ""

    private fun sha256(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
