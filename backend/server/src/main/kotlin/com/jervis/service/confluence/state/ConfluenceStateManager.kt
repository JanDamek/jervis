package com.jervis.service.confluence.state

import com.jervis.entity.confluence.ConfluencePageIndexDocument
import com.jervis.repository.ConfluencePageIndexMongoRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service

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
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    companion object {
        private const val POLL_DELAY_MS = 30_000L // 30 seconds when no NEW pages
    }

    /**
     * Continuous flow of NEW pages for given connection.
     * Polls DB every 30s when empty, never ends.
     */
    fun continuousNewPages(connectionId: ObjectId): Flow<ConfluencePageIndexDocument> =
        flow {
            while (true) {
                val pages =
                    repository.findByConnectionDocumentIdAndStateOrderByConfluenceUpdatedAtDesc(
                        connectionId,
                        "NEW",
                    )

                var emittedAny = false
                pages.collect { page ->
                    emit(page)
                    emittedAny = true
                }

                if (!emittedAny) {
                    logger.debug { "No NEW Confluence pages for connection $connectionId, sleeping ${POLL_DELAY_MS}ms" }
                    delay(POLL_DELAY_MS)
                } else {
                    logger.debug { "Processed NEW pages, immediately checking for more..." }
                }
            }
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
                    repository.findByStateOrderByConfluenceUpdatedAtDesc(
                        "NEW",
                    )

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

        // Delete old NEW document
        val deleteQuery = Query.query(Criteria.where("_id").`is`(page.id))
        mongoTemplate.remove(deleteQuery, ConfluencePageIndexDocument::class.java).block()

        // Insert minimal INDEXED document
        val indexed =
            ConfluencePageIndexDocument.Indexed(
                id = page.id,
                clientId = page.clientId,
                projectId = page.projectId,
                connectionDocumentId = page.connectionDocumentId,
                pageId = page.pageId,
                confluenceUpdatedAt = page.confluenceUpdatedAt,
            )
        repository.save(indexed)

        logger.info { "Marked Confluence page ${page.pageId} (${page.title}) as INDEXED (content cleaned)" }
    }

    /**
     * Mark page as FAILED with error message.
     * Keeps full content for retry.
     */
    suspend fun markAsFailed(
        page: ConfluencePageIndexDocument,
        reason: String,
    ) {
        // Convert NEW to FAILED, keeping all data
        when (page) {
            is ConfluencePageIndexDocument.New -> {
                // Delete old NEW document
                val deleteQuery = Query.query(Criteria.where("_id").`is`(page.id))
                mongoTemplate.remove(deleteQuery, ConfluencePageIndexDocument::class.java).block()

                // Insert FAILED document with full data
                val failed =
                    ConfluencePageIndexDocument.Failed(
                        id = page.id,
                        clientId = page.clientId,
                        projectId = page.projectId,
                        connectionDocumentId = page.connectionDocumentId,
                        pageId = page.pageId,
                        spaceKey = page.spaceKey,
                        title = page.title,
                        content = page.content,
                        parentPageId = page.parentPageId,
                        pageType = page.pageType,
                        status = page.status,
                        creator = page.creator,
                        lastModifier = page.lastModifier,
                        labels = page.labels,
                        comments = page.comments,
                        attachments = page.attachments,
                        createdAt = page.createdAt,
                        confluenceUpdatedAt = page.confluenceUpdatedAt,
                        indexingError = reason,
                    )
                repository.save(failed)
                logger.warn { "Marked Confluence page ${page.pageId} (${page.title}) as FAILED: $reason" }
            }

            is ConfluencePageIndexDocument.Failed -> {
                // Already FAILED, just update error message
                val deleteQuery = Query.query(Criteria.where("_id").`is`(page.id))
                mongoTemplate.remove(deleteQuery, ConfluencePageIndexDocument::class.java).block()

                val updated =
                    page.copy(
                        indexingError = "${page.indexingError}; $reason",
                    )
                repository.save(updated)
                logger.warn { "Updated FAILED Confluence page ${page.pageId} (${page.title}): $reason" }
            }

            is ConfluencePageIndexDocument.Indexed -> {
                logger.error { "Cannot mark INDEXED page as FAILED: ${page.pageId}" }
            }
        }
    }

    /**
     * Reset FAILED page back to NEW for retry.
     */
    suspend fun resetFailedToNew(page: ConfluencePageIndexDocument.Failed) {
        // Delete old FAILED document
        val deleteQuery = Query.query(Criteria.where("_id").`is`(page.id))
        mongoTemplate.remove(deleteQuery, ConfluencePageIndexDocument::class.java).block()

        // Insert NEW document with full data
        val newDoc =
            ConfluencePageIndexDocument.New(
                id = page.id,
                clientId = page.clientId,
                projectId = page.projectId,
                connectionDocumentId = page.connectionDocumentId,
                pageId = page.pageId,
                spaceKey = page.spaceKey,
                title = page.title,
                content = page.content,
                parentPageId = page.parentPageId,
                pageType = page.pageType,
                status = page.status,
                creator = page.creator,
                lastModifier = page.lastModifier,
                labels = page.labels,
                comments = page.comments,
                attachments = page.attachments,
                createdAt = page.createdAt,
                confluenceUpdatedAt = page.confluenceUpdatedAt,
            )
        repository.save(newDoc)
        logger.info { "Reset FAILED Confluence page ${page.pageId} (${page.title}) back to NEW for retry" }
    }
}
