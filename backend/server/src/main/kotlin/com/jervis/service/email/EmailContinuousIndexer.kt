package com.jervis.service.email

import com.jervis.entity.email.EmailMessageIndexDocument
import com.jervis.repository.EmailMessageIndexMongoRepository
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
 * Architecture:
 * - CentralPoller fetches FULL email data from IMAP/POP3 → stores in MongoDB as NEW
 * - This indexer reads NEW documents from MongoDB (NO email server calls)
 * - Chunks text, creates embeddings, stores to RAG
 * - Marks as INDEXED
 *
 * Pure ETL: MongoDB → RAG
 */
@Service
@Order(11) // Start after JiraContinuousIndexer
class EmailContinuousIndexer(
    private val repository: EmailMessageIndexMongoRepository,
    private val orchestrator: EmailIndexingOrchestrator,
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
        logger.debug { "Indexing email: ${doc.subject}" }

        // Mark as INDEXING to prevent concurrent processing
        markAsIndexing(doc)

        // Index to RAG (uses data already in MongoDB, NO email server calls)
        val result =
            orchestrator.indexSingleEmail(
                clientId = doc.clientId,
                document = doc,
            )

        if (result.success) {
            markAsIndexed(doc, result.chunkCount)
            logger.info { "Indexed email ${doc.subject}: ${result.chunkCount} chunks" }
        } else {
            markAsFailed(doc, "Indexing failed")
            logger.warn { "Failed to index email ${doc.subject}" }
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
