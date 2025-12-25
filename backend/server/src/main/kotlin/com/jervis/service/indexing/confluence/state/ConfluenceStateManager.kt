package com.jervis.service.indexing.confluence.state

import com.jervis.domain.PollingStatusEnum
import com.jervis.entity.confluence.ConfluencePageIndexDocument
import com.jervis.repository.ConfluencePageIndexRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Manages Confluence page indexing state transitions with a sealed class pattern.
 *
 * State transitions:
 * - NEW → INDEXED (success, delete full content)
 * - NEW → FAILED (error, keep full content)
 *
 * Content cleanup:
 * - NEW/FAILED: Full document in MongoDB
 * - INDEXED: Delete old doc, insert minimal tracking doc
 */
@Service
class ConfluenceStateManager(
    private val repository: ConfluencePageIndexRepository,
) {
    companion object {
        private const val POLL_DELAY_MS = 30_000L // 30 seconds when no NEW pages
    }

    /**
     * Continuous flow of NEW pages across ALL accounts (newest first).
     * Single indexer instance processes pages from all accounts,
     * ordered by confluenceUpdatedAt descending (newest pages prioritized).
     */
    fun continuousNewPagesAllAccounts(): Flow<ConfluencePageIndexDocument> =
        flow {
            while (true) {
                val pages =
                    repository.findAllByStatusOrderByConfluenceUpdatedAtDesc()

                var emittedAny = false
                pages.collect { page ->
                    emit(page)
                    emittedAny = true
                }

                if (!emittedAny) {
                    logger.debug { "No NEW Confluence pages across all accounts, sleeping ${POLL_DELAY_MS}ms" }
                    delay(POLL_DELAY_MS)
                } else {
                    logger.debug { "Processed NEW pages across all accounts, immediately checking for more..." }
                }
            }
        }

    /**
     * Mark page as INDEXED after successful PendingTask creation.
     * Deletes old NEW document, inserts minimal INDEXED document.
     *
     * This is the content cleanup step - removes full content, comments, attachments.
     */
    suspend fun markAsIndexed(page: ConfluencePageIndexDocument) {
        repository.save(page.copy(status = PollingStatusEnum.INDEXED))
    }

    /**
     * Mark the page as FAILED with an error message.
     * Keeps full content for retry.
     */
    suspend fun markAsFailed(
        page: ConfluencePageIndexDocument,
        reason: String,
    ) {
        repository.save(page.copy(status = PollingStatusEnum.FAILED, indexingError = reason))
    }
}
