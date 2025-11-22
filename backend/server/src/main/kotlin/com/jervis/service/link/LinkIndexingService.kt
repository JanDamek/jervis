package com.jervis.service.link

import com.jervis.domain.rag.RagSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

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
    private val pendingTaskService: com.jervis.service.background.PendingTaskService,
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
        parentRef: String? = null,
        correlationId: String? = null,
    ) = withContext(Dispatchers.IO) {
        val distinctLinksWithContext = extractLinksWithContext(text).distinctBy { it.url }
        if (distinctLinksWithContext.isEmpty()) return@withContext

        logger.debug { "Extracted ${distinctLinksWithContext.size} distinct links from text (source=$sourceType)" }

        data class QualificationStats(
            val safe: List<String> = emptyList(),
            val blocked: Int = 0,
            val uncertain: Int = 0,
        )

        val stats =
            distinctLinksWithContext
                .filterNot { isUnsupportedMedia(it.url) }
                .fold(QualificationStats()) { acc, linkWithContext ->
                    val safetyResult =
                        linkSafetyQualifier.qualifyLink(
                            url = linkWithContext.url,
                            contextBefore = linkWithContext.contextBefore,
                            contextAfter = linkWithContext.contextAfter,
                        )

                    when (safetyResult.decision) {
                        LinkSafetyQualifier.SafetyResult.Decision.SAFE -> {
                            acc.copy(safe = acc.safe + linkWithContext.url)
                        }
                        LinkSafetyQualifier.SafetyResult.Decision.UNSAFE -> {
                            logger.info { "Blocked unsafe link: ${linkWithContext.url} (${safetyResult.reason})" }
                            acc.copy(blocked = acc.blocked + 1)
                        }
                        LinkSafetyQualifier.SafetyResult.Decision.UNCERTAIN -> {
                            logger.warn { "Uncertain link - creating agent review task: ${linkWithContext.url}" }
                            createUncertainLinkTask(
                                linkWithContext = linkWithContext,
                                safetyReason = safetyResult.reason,
                                sourceType = sourceType,
                                clientId = clientId,
                                projectId = projectId,
                                parentRef = parentRef,
                                correlationId = correlationId,
                            )
                            acc.copy(uncertain = acc.uncertain + 1)
                        }
                    }
                }

        stats.takeIf { it.blocked > 0 }?.let {
            logger.info { "Blocked ${it.blocked} unsafe links (unsubscribe, tracking, etc.)" }
        }

        stats.takeIf { it.uncertain > 0 }?.let {
            logger.info { "Created ${it.uncertain} agent review tasks for uncertain links" }
        }

        if (stats.safe.isEmpty()) {
            logger.debug { "No safe links to index after qualification" }
            return@withContext
        }

        logger.info { "Indexing ${stats.safe.size} safe links (blocked ${stats.blocked}, uncertain ${stats.uncertain})" }

        stats.safe
            .map { linkUrl ->
                async {
                    runCatching {
                        linkIndexer.indexLink(
                            url = linkUrl,
                            projectId = projectId,
                            clientId = clientId,
                        )
                    }.onFailure { e ->
                        logger.warn { "Failed to index link $linkUrl. Error:${e.message}" }
                    }
                }
            }.awaitAll()

        logger.debug { "Completed indexing ${stats.safe.size} links" }
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
        skipSafetyCheck: Boolean = false,
    ): LinkIndexer.IndexResult {
        when {
            isUnsupportedMedia(url) -> {
                logger.info { "Ignoring unsupported media URL (image): $url" }
                return LinkIndexer.IndexResult(processedChunks = 0, skipped = true)
            }
            !skipSafetyCheck -> {
                val safetyResult = linkSafetyQualifier.qualifyLink(url)
                if (safetyResult.decision != LinkSafetyQualifier.SafetyResult.Decision.SAFE) {
                    logger.info { "Blocked unsafe URL: $url (${safetyResult.reason})" }
                    return LinkIndexer.IndexResult(processedChunks = 0, skipped = true)
                }
            }
        }

        return linkIndexer.indexLink(url, projectId, clientId)
    }

    /**
     * Represents a link with its surrounding context from the document.
     * Context helps LLM determine if link is safe (e.g., "security code: ..." indicates unsafe).
     */
    private data class LinkWithContext(
        val url: String,
        val contextBefore: String, // Text before the link (up to 150 chars)
        val contextAfter: String, // Text after the link (up to 150 chars)
    )

    /**
     * Create pending task for agent to review uncertain link.
     * The goal is defined in pending-tasks-goals.yaml for LINK_SAFETY_REVIEW type.
     * Agent will analyze context and decide: SAFE (index), UNSAFE (add pattern), or SKIP.
     */
    private suspend fun createUncertainLinkTask(
        linkWithContext: LinkWithContext,
        safetyReason: String,
        sourceType: RagSourceType,
        clientId: ObjectId,
        projectId: ObjectId?,
        parentRef: String?,
        correlationId: String? = null,
    ) {
        val taskContent =
            buildString {
                appendLine("Link Safety Review Required")
                appendLine()
                appendLine("URL: ${linkWithContext.url}")
                appendLine("Source: $sourceType")
                parentRef?.let { appendLine("Parent Reference: $it") }
                appendLine()
                appendLine("Initial Assessment: $safetyReason")
                appendLine()
                appendLine("=== CONTEXT AROUND LINK ===")
                if (linkWithContext.contextBefore.isNotBlank()) {
                    appendLine("Text BEFORE link:")
                    appendLine("\"...${linkWithContext.contextBefore}\"")
                    appendLine()
                }
                if (linkWithContext.contextAfter.isNotBlank()) {
                    appendLine("Text AFTER link:")
                    appendLine("\"${linkWithContext.contextAfter}...\"")
                }
            }

        runCatching {
            pendingTaskService.createTask(
                taskType = com.jervis.dto.PendingTaskTypeEnum.LINK_SAFETY_REVIEW,
                content = taskContent,
                clientId = clientId,
                projectId = projectId,
                correlationId = correlationId,
            )
            logger.debug { "Created LINK_SAFETY_REVIEW task for: ${linkWithContext.url}" }
        }.onFailure { e ->
            logger.error(e) { "Failed to create uncertain link task for ${linkWithContext.url}" }
        }
    }

    /**
     * Extract links with surrounding context (±150 chars around link).
     * Context is crucial for LLM to determine link safety:
     * - "click here to unsubscribe" → UNSAFE
     * - "documentation available at" → SAFE
     * - "security code: 123456. Click here" → UNSAFE
     * - "to cancel subscription" → UNSAFE
     */
    private fun extractLinksWithContext(content: String): List<LinkWithContext> {
        val urlPattern = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
        val contextRadius = 150

        return urlPattern
            .findAll(content)
            .mapNotNull { match ->
                val url = match.value

                if (url.startsWith("mailto:", ignoreCase = true)) return@mapNotNull null

                val cleaned =
                    cleanAndValidateUrl(url) ?: run {
                        logger.debug { "Skipping malformed URL: $url" }
                        return@mapNotNull null
                    }

                val cleanedUrl = removeTrackingParameters(cleaned)
                val startIndex = (match.range.first - contextRadius).coerceAtLeast(0)
                val endIndex = (match.range.last + 1 + contextRadius).coerceAtMost(content.length)

                LinkWithContext(
                    url = cleanedUrl,
                    contextBefore =
                        content
                            .substring(startIndex, match.range.first)
                            .replace(Regex("\\s+"), " ")
                            .trim(),
                    contextAfter =
                        content
                            .substring(match.range.last + 1, endIndex)
                            .replace(Regex("\\s+"), " ")
                            .trim(),
                )
            }.toList()
    }

    /**
     * Clean and validate URL, removing common malformations.
     *
     * Common issues:
     * - URLs with parentheses in fragment: `#gallery(https://...)`
     * - Duplicate URLs concatenated together
     * - Trailing punctuation from text extraction
     *
     * @return Cleaned URL or null if URL is invalid/malformed
     */
    private fun cleanAndValidateUrl(url: String): String? {
        val cleaned =
            url
                .trimEnd('.', ',', ')', ']', '}', '>', ';', '!')
                .let { cleaned ->
                    val fragmentIndex = cleaned.indexOf('#').takeIf { it > 0 } ?: return@let cleaned
                    val fragment = cleaned.substring(fragmentIndex + 1)

                    when {
                        fragment.contains("http://") || fragment.contains("https://") ->
                            cleaned.take(fragmentIndex)
                        fragment.contains(Regex("[(){}\\[\\]]")) ->
                            "${cleaned.take(fragmentIndex)}#${fragment.replace(Regex("[(){}\\[\\]]"), "")}"
                        else -> cleaned
                    }
                }

        return runCatching {
            java.net
                .URI(cleaned)
                .takeIf { it.scheme != null && it.host != null }
                ?.let { cleaned }
        }.onFailure {
            logger.debug { "Failed to parse URL even after cleaning: $cleaned (original: $url)" }
        }.getOrNull()
    }

    /**
     * Returns true if the URL points to an unsupported media type (currently common image formats)
     * which we do not download/index. This prevents unnecessary safety checks and tasks.
     */
    private fun isUnsupportedMedia(url: String): Boolean =
        runCatching {
            val path =
                java.net
                    .URI(url)
                    .path
                    .orEmpty()
                    .lowercase()
            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png")
        }.getOrElse {
            val lower = url.lowercase()
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
        }

    /**
     * Remove tracking/analytics parameters from URL.
     * These parameters don't affect content but make URLs unique per user/campaign.
     *
     * Removes:
     * - UTM parameters (utm_source, utm_medium, utm_campaign, utm_term, utm_content)
     * - Common tracking IDs (fbclid, gclid, msclkid, etc.)
     * - Analytics parameters (mc_cid, mc_eid, _ga, etc.)
     *
     * Keeps the base URL and any functional parameters intact.
     */
    private fun removeTrackingParameters(url: String): String =
        runCatching {
            val uri = java.net.URI(url)
            val query = uri.query ?: return url

            val trackingParams =
                setOf(
                    "utm_source",
                    "utm_medium",
                    "utm_campaign",
                    "utm_term",
                    "utm_content",
                    "fbclid",
                    "gclid",
                    "msclkid",
                    "twclid",
                    "li_fat_id",
                    "mc_cid",
                    "mc_eid",
                    "_ga",
                    "_gl",
                    "ref",
                    "source",
                )

            val cleanedParams =
                query
                    .split("&")
                    .filterNot { param ->
                        param.substringBefore("=").lowercase() in trackingParams
                    }

            val cleanedQuery = cleanedParams.takeIf { it.isNotEmpty() }?.joinToString("&")

            buildString {
                append("${uri.scheme}://${uri.authority}${uri.path.orEmpty()}")
                cleanedQuery?.let { append("?$it") }
                uri.fragment?.let { append("#$it") }
            }.also { rebuiltUrl ->
                if (rebuiltUrl != url) {
                    logger.debug { "Cleaned tracking params from URL: $url -> $rebuiltUrl" }
                }
            }
        }.getOrElse { e ->
            logger.debug { "Failed to parse URL for tracking param removal (already validated): $url - ${e.message}" }
            url
        }
}
