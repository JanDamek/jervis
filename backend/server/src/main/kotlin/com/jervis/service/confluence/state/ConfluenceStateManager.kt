package com.jervis.service.confluence.state

import com.jervis.entity.confluence.ConfluencePageIndexDocument
import com.jervis.repository.ConfluencePageIndexIndexMongoRepository
import com.jervis.repository.ConfluencePageIndexMongoRepository
import com.jervis.repository.ConfluencePageIndexNewMongoRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Manages Confluence page indexing state transitions with sealed class pattern.
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
    private val repository: ConfluencePageIndexMongoRepository,
    private val repositoryNew: ConfluencePageIndexNewMongoRepository,
    private val repositoryIndexed: ConfluencePageIndexIndexMongoRepository,
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
                    repositoryNew.findAllByOrderByConfluenceUpdatedAtDesc()

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
    suspend fun markAsIndexed(page: ConfluencePageIndexDocument.New) {
        repositoryNew.deleteById(page.id)

        val indexed =
            ConfluencePageIndexDocument.Indexed(
                id = ObjectId(),
                clientId = page.clientId,
                projectId = page.projectId,
                connectionDocumentId = page.connectionDocumentId,
                pageId = page.pageId,
                versionNumber = page.versionNumber,
                confluenceUpdatedAt = page.confluenceUpdatedAt,
            )
        repositoryIndexed.save(indexed)
    }

    /**
     * Mark page as FAILED with error message.
     * Keeps full content for retry.
     */
    suspend fun markAsFailed(
        page: ConfluencePageIndexDocument,
        reason: String,
    ) {
        when (page) {
            is ConfluencePageIndexDocument.New -> {
                repositoryNew.deleteById(page.id)

                val failed =
                    ConfluencePageIndexDocument.Failed(
                        id = ObjectId(),
                        clientId = page.clientId,
                        projectId = page.projectId,
                        connectionDocumentId = page.connectionDocumentId,
                        pageId = page.pageId,
                        versionNumber = page.versionNumber,
                        spaceKey = page.spaceKey,
                        title = page.title ?: "",
                        content = page.content,
                        parentPageId = page.parentPageId,
                        pageType = page.pageType ?: "",
                        status = page.status ?: "",
                        creator = page.creator,
                        lastModifier = page.lastModifier,
                        labels = page.labels ?: emptyList(),
                        comments = page.comments ?: emptyList(),
                        attachments = page.attachments ?: emptyList(),
                        createdAt = page.createdAt ?: Instant.now(),
                        confluenceUpdatedAt = page.confluenceUpdatedAt,
                        indexingError = reason,
                    )
                repository.save(failed)
            }

            is ConfluencePageIndexDocument.Failed -> {
                repository.deleteById(page.id)
                repository.save(page.copy(id = ObjectId(), indexingError = "${page.indexingError}; $reason"))
            }

            is ConfluencePageIndexDocument.Indexed -> {
                logger.error { "Cannot mark INDEXED page as FAILED: ${page.pageId}" }
            }
        }
    }
}
