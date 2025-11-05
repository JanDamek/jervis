package com.jervis.service.confluence.state

import com.jervis.domain.confluence.ConfluencePage
import com.jervis.domain.confluence.ConfluencePageStateEnum
import com.jervis.entity.ConfluencePageDocument
import com.jervis.repository.mongo.ConfluencePageMongoRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * State manager for Confluence page indexing - similar to EmailMessageStateManager and GitCommitStateManager.
 *
 * Responsibilities:
 * - Track which pages have been discovered and their versions
 * - Detect changes by comparing version numbers
 * - Provide continuous Flow of NEW pages for indexing
 * - Mark pages as INDEXED or FAILED after processing
 *
 * Change Detection:
 * - Compare lastKnownVersion with API version
 * - If version increased → mark as NEW for reindexing
 * - If version same → skip (already indexed)
 * - If page deleted from Confluence → keep in DB as historical record
 */
@Service
class ConfluencePageStateManager(
    private val pageRepository: ConfluencePageMongoRepository,
) {
    /**
     * Save or update page metadata from Confluence API.
     * Detects changes and marks for reindexing if needed.
     *
     * Returns true if page is NEW/changed and needs indexing.
     */
    suspend fun saveOrUpdatePage(
        accountId: ObjectId,
        clientId: ObjectId,
        projectId: ObjectId?,
        spaceKey: String,
        page: ConfluencePage,
        url: String,
    ): Boolean {
        val existing = pageRepository.findByAccountIdAndPageId(accountId, page.id)

        return if (existing == null) {
            // New page - save and mark as NEW
            val newPage =
                ConfluencePageDocument(
                    accountId = accountId,
                    clientId = clientId,
                    projectId = projectId,
                    pageId = page.id,
                    spaceKey = spaceKey,
                    title = page.title,
                    url = url,
                    lastKnownVersion = page.version.number,
                    state = ConfluencePageStateEnum.NEW,
                    parentPageId = page.parentId,
                    lastModifiedBy = page.version.authorId,
                    lastModifiedAt = page.version.createdAt,
                )

            pageRepository.save(newPage)
            logger.info { "Discovered new page: ${page.title} (${page.id}) v${page.version.number}" }
            true
        } else if (existing.lastKnownVersion < page.version.number) {
            // Version increased - content changed, mark as NEW for reindexing
            val updated =
                existing.copy(
                    title = page.title,
                    url = url,
                    lastKnownVersion = page.version.number,
                    state = ConfluencePageStateEnum.NEW,
                    parentPageId = page.parentId,
                    lastModifiedBy = page.version.authorId,
                    lastModifiedAt = page.version.createdAt,
                    updatedAt = Instant.now(),
                )

            pageRepository.save(updated)
            logger.info {
                "Detected change: ${page.title} (${page.id}) " +
                    "v${existing.lastKnownVersion} → v${page.version.number}"
            }
            true
        } else {
            // Version unchanged - no need to reindex
            logger.debug { "Page unchanged: ${page.title} (${page.id}) v${page.version.number}" }
            false
        }
    }

    /**
     * Continuous Flow of NEW pages for indexing.
     * Polls MongoDB every 30s when queue is empty.
     * Never terminates - similar to EmailMessageStateManager.continuousNewMessages().
     */
    fun continuousNewPages(accountId: ObjectId): Flow<ConfluencePageDocument> =
        flow {
            while (true) {
                var emittedAny = false

                pageRepository.findNewPagesByAccount(accountId).collect { page ->
                    emit(page)
                    emittedAny = true
                }

                if (!emittedAny) {
                    logger.debug { "No NEW pages for account $accountId, sleeping 30s..." }
                    delay(30_000)
                }
            }
        }

    /**
     * Mark page as successfully indexed.
     */
    suspend fun markAsIndexed(page: ConfluencePageDocument) {
        val updated =
            page.copy(
                state = ConfluencePageStateEnum.INDEXED,
                lastIndexedAt = Instant.now(),
                errorMessage = null,
                updatedAt = Instant.now(),
            )

        pageRepository.save(updated)
        logger.debug { "Marked page as INDEXED: ${page.title} (${page.pageId})" }
    }

    /**
     * Mark page as failed with error message.
     */
    suspend fun markAsFailed(
        page: ConfluencePageDocument,
        errorMessage: String,
    ) {
        val updated =
            page.copy(
                state = ConfluencePageStateEnum.FAILED,
                errorMessage = errorMessage.take(500), // Truncate long errors
                updatedAt = Instant.now(),
            )

        pageRepository.save(updated)
        logger.warn { "Marked page as FAILED: ${page.title} (${page.pageId}) - $errorMessage" }
    }

    /**
     * Update page links after content extraction.
     */
    suspend fun updatePageLinks(
        page: ConfluencePageDocument,
        internalLinks: List<String>,
        externalLinks: List<String>,
        childPageIds: List<String>,
    ) {
        val updated =
            page.copy(
                internalLinks = internalLinks,
                externalLinks = externalLinks,
                childPageIds = childPageIds,
                updatedAt = Instant.now(),
            )

        pageRepository.save(updated)
        logger.debug {
            "Updated links for page ${page.pageId}: " +
                "${internalLinks.size} internal, ${externalLinks.size} external, ${childPageIds.size} children"
        }
    }

    /**
     * Calculate content hash for duplicate detection.
     */
    fun calculateContentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Get indexing statistics for monitoring.
     */
    suspend fun getStats(accountId: ObjectId): ConfluenceIndexingStats {
        val newCount = pageRepository.countByAccountIdAndState(accountId, ConfluencePageStateEnum.NEW)
        val indexedCount = pageRepository.countByAccountIdAndState(accountId, ConfluencePageStateEnum.INDEXED)
        val failedCount = pageRepository.countByAccountIdAndState(accountId, ConfluencePageStateEnum.FAILED)

        return ConfluenceIndexingStats(
            accountId = accountId,
            newPages = newCount,
            indexedPages = indexedCount,
            failedPages = failedCount,
            totalPages = newCount + indexedCount + failedCount,
        )
    }
}

data class ConfluenceIndexingStats(
    val accountId: ObjectId,
    val newPages: Long,
    val indexedPages: Long,
    val failedPages: Long,
    val totalPages: Long,
)
