package com.jervis.service.email

import com.jervis.common.types.SourceUrn
import com.jervis.domain.PollingStatusEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.email.EmailDirection
import com.jervis.entity.email.EmailMessageIndexDocument
import com.jervis.repository.EmailMessageIndexRepository
import com.jervis.service.background.TaskService
import com.jervis.service.indexing.AttachmentExtractionService
import com.jervis.service.indexing.AttachmentInfo
import com.jervis.service.indexing.AttachmentKbIndexingService
import com.jervis.configuration.DocumentExtractionClient
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for email messages.
 *
 * ARCHITECTURE (Thread-Aware Routing):
 * - CentralPoller fetches FULL email data from IMAP/POP3 → stores in MongoDB as NEW
 * - This indexer reads NEW documents from MongoDB (NO email server calls)
 * - Thread consolidation: groups related emails into single tasks via topicId
 * - SENT emails: indexed to KB only (no task creation)
 * - User replied externally: auto-resolves existing USER_TASK
 * - Creates PendingTask for Qualifier (new conversations)
 * - Converts to INDEXED (minimal tracking record)
 *
 * DATA LIFECYCLE (single instance, no locking needed):
 * - NEW: Full data (textBody, htmlBody, attachments, metadata)
 * - INDEXED: Minimal (id, clientId, connectionDocumentId, messageUid, messageId, receivedDate)
 * - FAILED: Same as NEW + error field (for retry)
 */
@Service
@Order(11)
class EmailContinuousIndexer(
    private val repository: EmailMessageIndexRepository,
    private val taskService: TaskService,
    private val documentExtractionClient: DocumentExtractionClient,
    private val attachmentKbIndexingService: AttachmentKbIndexingService,
    private val attachmentExtractionService: AttachmentExtractionService,
    private val emailThreadService: EmailThreadService,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    companion object {
        private const val POLL_DELAY_MS = 30_000L // 30 seconds when no NEW emails
    }

    @PostConstruct
    fun start() {
        logger.info { "Starting EmailContinuousIndexer (MongoDB → RAG)..." }
        scope.launch {
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "Email continuous indexer crashed" } }
        }
    }

    private suspend fun indexContinuously() {
        continuousNewEmails().collect { doc ->
            if (doc.state != PollingStatusEnum.NEW) {
                logger.warn { "Skipping non-NEW email: ${doc.id} (state=${doc.state})" }
                return@collect
            }
            try {
                indexEmail(doc)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index email ${doc.messageUid}" }
                markAsFailed(doc, "Indexing error: ${e.message}")
            }
        }
    }

    private fun continuousNewEmails() =
        flow {
            while (true) {
                val emails = repository.findByStateOrderByReceivedDateAsc(PollingStatusEnum.NEW)

                var emittedAny = false
                emails.collect { email ->
                    emit(email)
                    emittedAny = true
                }

                if (!emittedAny) {
                    logger.debug { "No NEW emails, sleeping ${POLL_DELAY_MS}ms" }
                    delay(POLL_DELAY_MS)
                } else {
                    logger.debug { "Processed NEW emails, immediately checking for more..." }
                }
            }
        }

    /**
     * Index a single email message with thread-aware consolidation.
     *
     * Decision matrix:
     * | Situation                              | Action                                    |
     * |----------------------------------------|-------------------------------------------|
     * | SENT email                             | KB only (attachments), no task             |
     * | Incoming + user already replied in thread | Auto-resolve existing USER_TASK → DONE  |
     * | Incoming + existing task for thread     | Update task content, bump lastActivityAt  |
     * | Incoming + new thread or standalone     | Create new task with topicId              |
     */
    private suspend fun indexEmail(doc: EmailMessageIndexDocument) {
        require(doc.state == PollingStatusEnum.NEW) { "Can only index NEW emails, got: ${doc.state}" }
        logger.debug { "Processing email: ${doc.subject}" }

        try {
            // SENT emails: thread-aware — find existing task, update content, auto-resolve
            if (doc.direction == EmailDirection.SENT) {
                val threadContext = emailThreadService.analyzeThread(doc)
                if (threadContext?.existingTask != null) {
                    // Update task with our reply content + thread summary, then auto-resolve
                    val sentContent = buildEmailContent(doc)
                    val updatedContent = buildString {
                        append(sentContent)
                        append("\n\n")
                        append(threadContext.threadSummary)
                    }
                    taskService.updateThreadActivity(threadContext.existingTask.id, updatedContent)
                    taskService.resolveAsHandledExternally(threadContext.existingTask.id)
                    logger.info {
                        "SENT email paired with task ${threadContext.existingTask.id} → auto-resolved: ${doc.subject}"
                    }
                }
                indexEmailAttachments(doc)
                markAsIndexed(doc)
                if (threadContext?.existingTask == null) {
                    logger.info { "SENT email indexed (KB only, no matching task): ${doc.subject}" }
                }
                return
            }

            // Analyze thread context for incoming emails
            val threadContext = emailThreadService.analyzeThread(doc)

            // If user already replied in this thread → auto-resolve any existing USER_TASK
            if (threadContext != null && threadContext.userReplied) {
                if (threadContext.existingTask != null) {
                    taskService.resolveAsHandledExternally(threadContext.existingTask.id)
                }
                indexEmailAttachments(doc)
                markAsIndexed(doc)
                logger.info { "Thread auto-resolved (user replied externally): ${doc.subject}" }
                return
            }

            // Build email content for task
            val emailContent = buildEmailContent(doc)
            val attachmentsWithStorage = doc.attachments.filter { it.storagePath != null }
            val attachmentInfos = attachmentsWithStorage.map { att ->
                AttachmentInfo(
                    filename = att.filename,
                    mimeType = att.contentType,
                    sizeBytes = att.size,
                    storagePath = att.storagePath,
                )
            }

            // Thread with existing task → update task content with thread summary
            if (threadContext?.existingTask != null) {
                val updatedContent = buildString {
                    append(emailContent)
                    append("\n\n")
                    append(threadContext.threadSummary)
                }
                taskService.updateThreadActivity(threadContext.existingTask.id, updatedContent)
                indexEmailAttachments(doc)
                markAsIndexed(doc)
                logger.info {
                    "Thread consolidated into existing task ${threadContext.existingTask.id}: ${doc.subject} " +
                        "(${threadContext.messageCount} messages)"
                }
                return
            }

            // New email (standalone or first in thread) → create new task
            val topicId = when {
                threadContext != null -> threadContext.topicId
                doc.threadId != null -> "email-thread:${doc.threadId}"
                else -> null
            }

            val task = taskService.createTask(
                taskType = TaskTypeEnum.EMAIL_PROCESSING,
                content = if (threadContext != null) {
                    "$emailContent\n\n${threadContext.threadSummary}"
                } else {
                    emailContent
                },
                clientId = doc.clientId,
                correlationId = "email:${doc.id}",
                sourceUrn = SourceUrn.email(
                    connectionId = doc.connectionId,
                    messageId = doc.messageId ?: doc.messageUid,
                    subject = doc.subject ?: "",
                ),
                projectId = doc.projectId,
                taskName = doc.subject?.take(120) ?: "Email from ${doc.from}",
                hasAttachments = attachmentsWithStorage.isNotEmpty(),
                attachmentCount = attachmentsWithStorage.size,
            )

            // Set topicId for thread consolidation (future messages in same thread will find this task)
            if (topicId != null) {
                taskService.setTopicId(task.id, topicId)
            }

            // Create extract records and trigger async text extraction for Qualifier
            if (attachmentsWithStorage.isNotEmpty()) {
                try {
                    val created = attachmentExtractionService.createExtractsForAttachments(
                        taskId = task.id.toString(),
                        attachments = attachmentInfos,
                    )
                    if (created > 0) {
                        scope.launch {
                            try {
                                attachmentExtractionService.processPendingExtracts(task.id.toString())
                            } catch (e: Exception) {
                                logger.warn(e) { "Async text extraction failed for task ${task.id}" }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to create attachment extract records for email '${doc.subject}'" }
                }
            }

            indexEmailAttachments(doc)
            markAsIndexed(doc)
            logger.info { "Created EMAIL_PROCESSING task for email: ${doc.subject} (topicId=$topicId)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task for email ${doc.subject}" }
            markAsFailed(doc, "Task creation failed: ${e.message}")
        }
    }

    private suspend fun buildEmailContent(doc: EmailMessageIndexDocument): String {
        val rawEmailBody = doc.textBody ?: doc.htmlBody ?: ""
        val emailBody = documentExtractionClient.extractText(rawEmailBody, "text/html")

        return buildString {
            append("# Email: ${doc.subject}\n")
            append("From: ${doc.from} | Date: ${doc.sentDate ?: doc.receivedDate}\n\n")

            if (emailBody.isNotBlank()) {
                append(emailBody)
                append("\n\n")
            }

            append("---\n\n")
            append("## Email Metadata\n")
            append("- **From:** ${doc.from}\n")
            if (doc.to.isNotEmpty()) {
                append("- **To:** ${doc.to.joinToString(", ")}\n")
            }
            if (doc.cc.isNotEmpty()) {
                append("- **Cc:** ${doc.cc.joinToString(", ")}\n")
            }
            append("- **Date:** ${doc.sentDate ?: doc.receivedDate}\n")
            append("- **Folder:** ${doc.folder}\n")
            append("- **Message-ID:** ${doc.messageId}\n")
            append("- **Email ID:** ${doc.id}\n")
            append("- **Connection ID:** ${doc.connectionId}\n")
            append("- **Client ID:** ${doc.clientId}\n")

            if (doc.attachments.isNotEmpty()) {
                append("\n## Attachments\n")
                doc.attachments.forEach { att ->
                    append("- ${att.filename} (${att.contentType}, ${att.size} bytes)\n")
                }
            }
        }
    }

    /**
     * Index email attachments as KB documents.
     *
     * Attachments with storagePath were stored during email polling (binary extracted from MIME
     * and written to kb-documents/ directory). This method registers them with the KB service
     * for text extraction and RAG indexing, making them searchable directly in the Knowledge Base.
     */
    private suspend fun indexEmailAttachments(doc: EmailMessageIndexDocument) {
        val attachmentsWithStorage = doc.attachments.filter { it.storagePath != null }
        if (attachmentsWithStorage.isEmpty()) return

        val messageId = doc.messageId ?: doc.messageUid
        val emailSubject = doc.subject ?: "Email from ${doc.from}"

        logger.info {
            "Indexing ${attachmentsWithStorage.size} email attachment(s) as KB documents: subject='$emailSubject'"
        }

        for (att in attachmentsWithStorage) {
            try {
                val sourceUrn = SourceUrn.emailAttachment(
                    connectionId = doc.connectionId,
                    messageId = messageId,
                    filename = att.filename,
                )

                // Binary was already stored in kb-documents/ during polling — just register it
                attachmentKbIndexingService.registerPreStoredAttachment(
                    clientId = doc.clientId,
                    projectId = doc.projectId,
                    filename = att.filename,
                    mimeType = att.contentType,
                    sizeBytes = att.size,
                    kbDocumentStoragePath = att.storagePath!!,
                    sourceUrn = sourceUrn,
                    title = "Email: ${att.filename}",
                    description = "Attachment from email '$emailSubject' (from: ${doc.from}, date: ${doc.sentDate ?: doc.receivedDate})",
                    tags = listOf("email-attachment", "email"),
                )
            } catch (e: Exception) {
                // Don't fail the whole email indexing if one attachment fails
                logger.warn(e) {
                    "Failed to index email attachment as KB document: ${att.filename} " +
                        "from email '${doc.subject}'"
                }
            }
        }
    }

    private suspend fun markAsIndexed(doc: EmailMessageIndexDocument) {
        val updated =
            EmailMessageIndexDocument(
                id = doc.id,
                clientId = doc.clientId,
                projectId = doc.projectId,
                connectionId = doc.connectionId,
                messageUid = doc.messageUid,
                messageId = doc.messageId,
                receivedDate = doc.receivedDate,
                state = PollingStatusEnum.INDEXED,
            )
        repository.save(updated)
        logger.debug { "Marked email as INDEXED (minimal tracking): ${doc.messageUid}" }
    }

    private suspend fun markAsFailed(
        doc: EmailMessageIndexDocument,
        error: String,
    ) {
        val updated =
            doc.copy(
                state = PollingStatusEnum.FAILED,
                indexingError = error,
            )
        repository.save(updated)
        logger.warn { "Marked email as FAILED: ${doc.subject}" }
    }
}
