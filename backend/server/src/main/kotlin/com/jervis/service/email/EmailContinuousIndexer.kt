package com.jervis.service.email

import com.jervis.common.types.SourceUrn
import com.jervis.domain.PollingStatusEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.email.EmailMessageIndexDocument
import com.jervis.repository.EmailMessageIndexRepository
import com.jervis.service.background.TaskService
import com.jervis.service.text.TikaTextExtractionService
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

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for email messages.
 *
 * ARCHITECTURE (Graph-Based Routing):
 * - CentralPoller fetches FULL email data from IMAP/POP3 → stores in MongoDB as NEW
 * - This indexer reads NEW documents from MongoDB (NO email server calls)
 * - Creates PendingTask for Qualifier
 * - Converts to INDEXED (minimal tracking record)
 * - Email content now lives in RAG/Graph, accessible via sourceUrn
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
    private val tikaTextExtractionService: TikaTextExtractionService,
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

    private suspend fun indexEmail(doc: EmailMessageIndexDocument) {
        require(doc.state == PollingStatusEnum.NEW) { "Can only index NEW emails, got: ${doc.state}" }
        logger.debug { "Processing email: ${doc.subject}" }

        try {
            // Get email body and clean it through Tika (removes HTML/XML formatting)
            val rawEmailBody = doc.textBody ?: doc.htmlBody ?: ""
            val emailBody =
                tikaTextExtractionService.extractPlainText(
                    content = rawEmailBody,
                    fileName = "email-${doc.id}.html",
                )

            val emailContent =
                buildString {
                    append("# Email: ${doc.subject}\n\n")
                    append("**From:** ${doc.from}\n")
                    if (doc.to.isNotEmpty()) {
                        append("**To:** ${doc.to.joinToString(", ")}\n")
                    }
                    if (doc.cc.isNotEmpty()) {
                        append("**Cc:** ${doc.cc.joinToString(", ")}\n")
                    }
                    append("**Date:** ${doc.sentDate ?: doc.receivedDate}\n")
                    append("**Folder:** ${doc.folder}\n")
                    append("**Message-ID:** ${doc.messageId}\n")
                    append("\n---\n\n")

                    if (emailBody.isNotBlank()) {
                        append("## Email Body\n\n")
                        append(emailBody)
                        append("\n\n")
                    }

                    if (doc.attachments.isNotEmpty()) {
                        append("## Email Attachments\n\n")
                        doc.attachments.forEach { att ->
                            append("- ${att.filename} (${att.contentType}, ${att.size} bytes)\n")
                        }
                        append("\n")
                    }

                    append("## Source Metadata\n")
                    append("- **Source Type:** Email\n")
                    append("- **Email ID:** ${doc.id}\n")
                    append("- **Message-ID:** ${doc.messageId}\n")
                    append("- **Subject:** ${doc.subject}\n")
                    append("- **From:** ${doc.from}\n")
                    append("- **To:** ${doc.to.joinToString(", ")}\n")
                    if (doc.cc.isNotEmpty()) {
                        append("- **Cc:** ${doc.cc.joinToString(", ")}\n")
                    }
                    append("- **Sent Date:** ${doc.sentDate}\n")
                    append("- **Received Date:** ${doc.receivedDate}\n")
                    append("- **Folder:** ${doc.folder}\n")
                    append("- **ConnectionDocument ID:** ${doc.connectionId}\n")
                    append("- **Client ID:** ${doc.clientId}\n")
                }

            taskService.createTask(
                taskType = TaskTypeEnum.EMAIL_PROCESSING,
                content = emailContent,
                clientId = doc.clientId,
                correlationId = "email:${doc.id}",
                sourceUrn =
                    SourceUrn.email(
                        connectionId = doc.connectionId,
                        messageId = doc.messageId ?: doc.messageUid,
                        subject = doc.subject ?: "",
                    ),
                projectId = doc.projectId,
            )

            markAsIndexed(doc)
            logger.info { "Created EMAIL_PROCESSING task for email: ${doc.subject}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task for email ${doc.subject}" }
            markAsFailed(doc, "Task creation failed: ${e.message}")
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
