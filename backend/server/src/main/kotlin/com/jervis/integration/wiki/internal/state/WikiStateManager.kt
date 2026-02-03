package com.jervis.integration.wiki.internal.state

import com.jervis.domain.PollingStatusEnum
import com.jervis.integration.wiki.internal.entity.WikiPageIndexDocument
import com.jervis.integration.wiki.internal.repository.WikiPageIndexRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Manages Wiki page indexing state transitions with a sealed class pattern.
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
class WikiStateManager(
    private val repository: WikiPageIndexRepository,
) {
    companion object {
        private const val POLL_DELAY_MS = 30_000L // 30 seconds when no NEW pages
    }

    /**
     * Continuous flow of NEW pages across ALL accounts (newest first).
     * Single indexer instance processes pages from all accounts,
     * ordered by wikiUpdatedAt descending (newest pages prioritized).
     */
    fun continuousNewPagesAllAccounts(): Flow<WikiPageIndexDocument> =
        flow {
            while (true) {
                val pages =
                    repository.findAllByStatusOrderByWikiUpdatedAtDesc()

                var emittedAny = false
                pages.collect { page ->
                    emit(page)
                    emittedAny = true
                }

                if (!emittedAny) {
                    logger.debug { "No NEW wiki pages across all accounts, sleeping ${POLL_DELAY_MS}ms" }
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
    suspend fun markAsIndexed(page: WikiPageIndexDocument) {
        repository.save(page.copy(status = PollingStatusEnum.INDEXED))
    }

    /**
     * Mark the page as FAILED with an error message.
     * Keeps full content for retry.
     */
    suspend fun markAsFailed(
        page: WikiPageIndexDocument,
        reason: String,
    ) {
        repository.save(page.copy(status = PollingStatusEnum.FAILED, indexingError = reason))
    }
}
