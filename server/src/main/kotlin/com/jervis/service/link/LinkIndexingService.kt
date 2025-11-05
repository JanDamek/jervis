package com.jervis.service.link

import com.jervis.domain.rag.RagSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * General link indexing service.
 * Used by email indexing, web content tools, and any other source that needs to index URLs.
 *
 * Handles:
 * - Extracting links from text
 * - Safety qualification (prevents indexing unsubscribe/tracking links)
 * - Parallel indexing of multiple links
 * - Single link indexing
 * - Skip logic if already in RAG
 */
@Service
class LinkIndexingService(
    private val linkIndexer: LinkIndexer,
    private val linkSafetyQualifier: LinkSafetyQualifier,
) {
    /**
     * Index all links found in text content.
     * Links are processed in parallel AFTER safety qualification.
     */
    suspend fun indexLinksFromText(
        text: String,
        projectId: ObjectId?,
        clientId: ObjectId,
        sourceType: RagSourceType = RagSourceType.EMAIL_LINK_CONTENT,
        createdAt: Instant = Instant.now(),
        parentRef: String? = null,
    ) = withContext(Dispatchers.IO) {
        val allLinks = extractLinks(text)
        if (allLinks.isEmpty()) return@withContext
        // Deduplicate to avoid repeated indexing attempts for identical URLs within the same batch
        val distinctLinks = allLinks.distinct()

        logger.debug { "Extracted ${allLinks.size} links from text (source=$sourceType)" }

        // Safety qualification: filter out unsubscribe, tracking, auth links
        val safeLinks = linkSafetyQualifier.filterSafeLinks(distinctLinks).toList()
        val blockedCount = allLinks.size - safeLinks.size

        if (blockedCount > 0) {
            logger.info { "Blocked $blockedCount unsafe links (unsubscribe, tracking, etc.)" }
        }

        if (safeLinks.isEmpty()) {
            logger.debug { "No safe links to index after qualification" }
            return@withContext
        }

        logger.info { "Indexing ${safeLinks.size} safe links (blocked $blockedCount)" }

        // Process safe links in parallel - each link download is independent
        safeLinks
            .map { linkUrl ->
                async {
                    try {
                        linkIndexer.indexLink(
                            url = linkUrl,
                            projectId = projectId,
                            clientId = clientId,
                            sourceType = sourceType,
                            createdAt = createdAt,
                            emailMessageId = parentRef,
                        )
                    } catch (e: Exception) {
                        logger.warn { "Failed to index link $linkUrl. Error:${e.message}" }
                    }
                }
            }.awaitAll()

        logger.debug { "Completed indexing ${safeLinks.size} links" }
    }

    /**
     * Index a single URL.
     * Used by web content tools or when URL is already known.
     * Performs safety check before indexing.
     */
    suspend fun indexUrl(
        url: String,
        projectId: ObjectId?,
        clientId: ObjectId,
        sourceType: RagSourceType = RagSourceType.URL,
        createdAt: Instant = Instant.now(),
        parentRef: String? = null,
        skipSafetyCheck: Boolean = false,
    ): LinkIndexer.IndexResult {
        // Safety check (unless explicitly skipped by caller who already validated)
        if (!skipSafetyCheck) {
            val safetyResult = linkSafetyQualifier.qualifyLink(url)
            if (safetyResult.decision != LinkSafetyQualifier.SafetyResult.Decision.SAFE) {
                logger.info { "Blocked unsafe URL: $url (${safetyResult.reason})" }
                return LinkIndexer.IndexResult(processedChunks = 0, skipped = true)
            }
        }

        return linkIndexer.indexLink(
            url = url,
            projectId = projectId,
            clientId = clientId,
            sourceType = sourceType,
            createdAt = createdAt,
            emailMessageId = parentRef,
        )
    }

    private fun extractLinks(content: String): List<String> {
        val urlPattern = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
        return urlPattern.findAll(content).map { it.value }.toList()
    }
}
