package com.jervis.service.email

import com.jervis.dto.PendingTaskStateEnum
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.entity.email.EmailMessageIndexDocument
import com.jervis.repository.EmailMessageIndexMongoRepository
import com.jervis.service.background.PendingTaskService
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
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - CentralPoller fetches FULL email data from IMAP/POP3 → stores in MongoDB as NEW
 * - This indexer reads NEW documents from MongoDB (NO email server calls)
 * - Creates DATA_PROCESSING PendingTask instead of auto-indexing
 * - Task goes to KoogQualifierAgent (CPU) for structuring:
 *   - Chunks large emails with overlap
 *   - Creates Graph nodes (email metadata)
 *   - Creates RAG chunks (searchable content)
 *   - Links Graph ↔ RAG bi-directionally
 * - After qualification: task marked DONE or READY_FOR_GPU
 *
 * Pure ETL: MongoDB → PendingTask → Qualifier → Graph + RAG
 */
@Service
@Order(11) // Start after JiraContinuousIndexer
class EmailContinuousIndexer(
    private val repository: EmailMessageIndexMongoRepository,
    private val pendingTaskService: PendingTaskService,
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
        // Continuous flow of NEW emails from MongoDB
        continuousNewEmails().collect { doc ->
            try {
                indexEmail(doc)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index email ${doc.subject}" }
                markAsFailed(doc, "Indexing error: ${e.message}")
            }
        }
    }

    private fun continuousNewEmails() =
        flow {
            while (true) {
                val emails = repository.findByStateOrderByReceivedDateAsc("NEW")

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
        logger.debug { "Processing email: ${doc.subject}" }

        // Mark as INDEXING to prevent concurrent processing
        markAsIndexing(doc)

        try {
            // Build email content for task
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
                    append("**Message-ID:** ${doc.messageId ?: "none"}\n")
                    append("\n---\n\n")

                    // Prefer text body, fallback to HTML
                    val body = doc.textBody ?: doc.htmlBody
                    if (!body.isNullOrBlank()) {
                        append(body)
                    }

                    if (doc.attachments.isNotEmpty()) {
                        append("\n\n## Attachments\n")
                        doc.attachments.forEach { att ->
                            append("- ${att.filename} (${att.contentType}, ${att.size} bytes)\n")
                        }
                    }

                    // Add metadata for qualifier
                    append("\n\n## Document Metadata\n")
                    append("- **Source:** Email (${doc.folder})\n")
                    append("- **Document ID:** email:${doc.id.toHexString()}\n")
                    append("- **Connection ID:** ${doc.connectionId.toHexString()}\n")
                }

            // Create DATA_PROCESSING task instead of auto-indexing
            pendingTaskService.createTask(
                taskType = PendingTaskTypeEnum.DATA_PROCESSING,
                content = emailContent,
                projectId = null,
                clientId = doc.clientId,
                correlationId = "email:${doc.id.toHexString()}",
            )

            // Mark as task created (reusing INDEXED state for now)
            markAsIndexed(doc, 0)
            logger.info { "Created DATA_PROCESSING task for email: ${doc.subject}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task for email ${doc.subject}" }
            markAsFailed(doc, "Task creation failed: ${e.message}")
        }
    }

    private suspend fun markAsIndexing(doc: EmailMessageIndexDocument) {
        val updated =
            doc.copy(
                state = "INDEXING",
                updatedAt = Instant.now(),
            )
        repository.save(updated)
        logger.debug { "Marked email as INDEXING: ${doc.subject}" }
    }

    private suspend fun markAsIndexed(
        doc: EmailMessageIndexDocument,
        chunkCount: Int,
    ) {
        val updated =
            doc.copy(
                state = "INDEXED",
                indexedAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        repository.save(updated)
        logger.debug { "Marked email as INDEXED: ${doc.subject} ($chunkCount chunks)" }
    }

    private suspend fun markAsFailed(
        doc: EmailMessageIndexDocument,
        error: String,
    ) {
        val updated =
            doc.copy(
                state = "FAILED",
                indexingError = error,
                updatedAt = Instant.now(),
            )
        repository.save(updated)
        logger.warn { "Marked email as FAILED: ${doc.subject}" }
    }
}
