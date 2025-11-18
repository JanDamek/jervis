package com.jervis.service.indexing

import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Central queue for cross-indexer URL handoff.
 *
 * Architecture:
 * - Confluence indexer finds Jira issue link → submits to queue → Jira indexer picks it up
 * - Jira indexer finds Confluence page link → submits to queue → Confluence indexer picks it up
 * - If no indexer can handle URL → creates user task
 *
 * Thread-safe for concurrent submissions from multiple indexers.
 */
@Service
class LinkIndexingQueue {
    private val pendingUrls = ConcurrentHashMap<String, PendingLink>()

    data class PendingLink(
        val url: String,
        val clientId: ObjectId,
        val projectId: ObjectId?,
        val sourceIndexer: String, // "Confluence", "Jira", "Email"
        val sourceRef: String, // e.g., pageId, issueKey, emailId
        val attemptCount: Int = 0,
        val maxAttempts: Int = 3,
    )

    /**
     * Submit URL for indexing by appropriate indexer.
     * Returns true if queued, false if rejected (duplicate or invalid).
     */
    fun submitUrl(
        url: String,
        clientId: ObjectId,
        projectId: ObjectId?,
        sourceIndexer: String,
        sourceRef: String,
    ): Boolean {
        // Normalize URL
        val normalizedUrl = normalizeUrl(url)

        // Check if already queued
        if (pendingUrls.containsKey(normalizedUrl)) {
            logger.debug { "URL already queued: $normalizedUrl" }
            return false
        }

        // Determine target indexer
        val targetIndexer = determineIndexer(normalizedUrl)
        if (targetIndexer == null) {
            logger.warn { "No indexer can handle URL: $normalizedUrl (source: $sourceIndexer)" }
            return false
        }

        if (targetIndexer == sourceIndexer) {
            logger.debug { "URL belongs to same indexer, skipping: $normalizedUrl" }
            return false
        }

        val link =
            PendingLink(
                url = normalizedUrl,
                clientId = clientId,
                projectId = projectId,
                sourceIndexer = sourceIndexer,
                sourceRef = sourceRef,
            )

        pendingUrls[normalizedUrl] = link
        logger.info { "LINK_QUEUED: url=$normalizedUrl target=$targetIndexer source=$sourceIndexer ref=$sourceRef" }
        return true
    }

    /**
     * Poll next URL for given indexer type.
     * Returns null if queue empty.
     */
    fun pollForIndexer(indexerType: String): PendingLink? {
        val entry =
            pendingUrls.entries.firstOrNull { (url, _) ->
                determineIndexer(url) == indexerType
            }

        return entry?.let {
            pendingUrls.remove(it.key)
            it.value
        }
    }

    /**
     * Mark URL as failed (couldn't be indexed).
     * If max attempts reached, return true to signal fallback to user task.
     */
    fun markFailed(
        url: String,
        reason: String,
    ): Boolean {
        val link = pendingUrls[url] ?: return false

        val updated = link.copy(attemptCount = link.attemptCount + 1)

        return if (updated.attemptCount >= updated.maxAttempts) {
            logger.warn { "URL failed after ${updated.attemptCount} attempts: $url reason=$reason" }
            pendingUrls.remove(url)
            true // Signal to create user task
        } else {
            pendingUrls[url] = updated
            logger.debug { "URL retry ${updated.attemptCount}/${updated.maxAttempts}: $url" }
            false
        }
    }

    /**
     * Get queue size for monitoring.
     */
    fun getQueueSize(): Int = pendingUrls.size

    /**
     * Determine which indexer should handle this URL.
     */
    private fun determineIndexer(url: String): String? =
        when {
            // Jira issue links
            url.contains("/browse/") -> "Jira"
            url.contains("/rest/api/") && url.contains("/issue/") -> "Jira"

            // Confluence page links
            url.contains("/wiki/spaces/") -> "Confluence"
            url.contains("/wiki/x/") -> "Confluence"
            url.contains("/pages/") -> "Confluence"

            // External links - generic link indexer
            else -> null
        }

    /**
     * Normalize URL for deduplication.
     */
    private fun normalizeUrl(url: String): String =
        url
            .trim()
            .removeSuffix("/")
            .lowercase()
            .replace(Regex("\\?.*$"), "") // Remove query params
            .replace(Regex("#.*$"), "") // Remove anchors
}
