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
        createdAt: Instant = Instant.now(),
        parentRef: String? = null,
    ) = withContext(Dispatchers.IO) {
        val allLinksWithContext = extractLinksWithContext(text)
        if (allLinksWithContext.isEmpty()) return@withContext

        // Deduplicate by URL (keep first occurrence with its context)
        val distinctLinksWithContext = allLinksWithContext.distinctBy { it.url }

        logger.debug { "Extracted ${allLinksWithContext.size} links from text (source=$sourceType)" }

        // Safety qualification with context: filter out unsubscribe, tracking, auth links
        val safeLinks = mutableListOf<String>()
        var blockedCount = 0
        var uncertainCount = 0

        for (linkWithContext in distinctLinksWithContext) {
            val safetyResult = linkSafetyQualifier.qualifyLink(
                url = linkWithContext.url,
                contextBefore = linkWithContext.contextBefore,
                contextAfter = linkWithContext.contextAfter,
            )

            when (safetyResult.decision) {
                LinkSafetyQualifier.SafetyResult.Decision.SAFE -> {
                    safeLinks.add(linkWithContext.url)
                }
                LinkSafetyQualifier.SafetyResult.Decision.UNSAFE -> {
                    logger.info { "Blocked unsafe link: ${linkWithContext.url} (${safetyResult.reason})" }
                    blockedCount++
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
                    )
                    uncertainCount++
                }
            }
        }

        if (blockedCount > 0) {
            logger.info { "Blocked $blockedCount unsafe links (unsubscribe, tracking, etc.)" }
        }

        if (uncertainCount > 0) {
            logger.info { "Created $uncertainCount agent review tasks for uncertain links" }
        }

        if (safeLinks.isEmpty()) {
            logger.debug { "No safe links to index after qualification" }
            return@withContext
        }

        logger.info { "Indexing ${safeLinks.size} safe links (blocked $blockedCount, uncertain $uncertainCount)" }

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
     * Agent will analyze and decide: SAFE (index), UNSAFE (add pattern), or SKIP.
     */
    private suspend fun createUncertainLinkTask(
        linkWithContext: LinkWithContext,
        safetyReason: String,
        sourceType: RagSourceType,
        clientId: ObjectId,
        projectId: ObjectId?,
        parentRef: String?,
    ) {
        val taskContent = buildString {
            appendLine("=== UNCERTAIN LINK - AGENT REVIEW NEEDED ===")
            appendLine()
            appendLine("URL: ${linkWithContext.url}")
            appendLine("Source: $sourceType")
            if (parentRef != null) {
                appendLine("Parent reference: $parentRef")
            }
            appendLine()
            appendLine("=== SAFETY ANALYSIS ===")
            appendLine("Reason: $safetyReason")
            appendLine()
            appendLine("=== TEXT CONTEXT AROUND LINK ===")
            if (linkWithContext.contextBefore.isNotBlank()) {
                appendLine("Text BEFORE link:")
                appendLine("\"...${linkWithContext.contextBefore}\"")
                appendLine()
            }
            if (linkWithContext.contextAfter.isNotBlank()) {
                appendLine("Text AFTER link:")
                appendLine("\"${linkWithContext.contextAfter}...\"")
                appendLine()
            }
            appendLine("=== AGENT ACTIONS ===")
            appendLine("Use MCP tools to:")
            appendLine("1. If SAFE: Call index_url(url='${linkWithContext.url}', skip_safety_check=true)")
            appendLine("2. If UNSAFE: Call manage_link_safety(action='add_pattern', pattern='...', reason='...')")
            appendLine("3. If SKIP: Just mark this task as complete without action")
            appendLine()
            appendLine("Consider:")
            appendLine("- Does context indicate action (unsubscribe, confirm, RSVP)?")
            appendLine("- Is domain trustworthy?")
            appendLine("- Are there suspicious query parameters (token=, code=)?")
        }

        try {
            pendingTaskService.createTask(
                taskType = com.jervis.domain.task.PendingTaskTypeEnum.LINK_SAFETY_REVIEW,
                content = taskContent,
                clientId = clientId,
                projectId = projectId,
            )

            logger.debug { "Created LINK_SAFETY_REVIEW task for: ${linkWithContext.url}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create uncertain link task for ${linkWithContext.url}" }
            // Don't fail indexing if task creation fails
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
        val contextRadius = 150 // Characters before/after link

        return urlPattern.findAll(content)
            .mapNotNull { match ->
                val url = match.value

                // Filter out mailto: links
                if (url.startsWith("mailto:", ignoreCase = true)) {
                    return@mapNotNull null
                }

                // Clean and validate URL
                val cleaned = cleanAndValidateUrl(url) ?: run {
                    logger.debug { "Skipping malformed URL: $url" }
                    return@mapNotNull null
                }

                val cleanedUrl = removeTrackingParameters(cleaned)

                // Extract context around the link
                val startIndex = maxOf(0, match.range.first - contextRadius)
                val endIndex = minOf(content.length, match.range.last + 1 + contextRadius)

                val contextBefore = content.substring(startIndex, match.range.first)
                    .replace(Regex("\\s+"), " ") // Normalize whitespace
                    .trim()

                val contextAfter = content.substring(match.range.last + 1, endIndex)
                    .replace(Regex("\\s+"), " ") // Normalize whitespace
                    .trim()

                LinkWithContext(
                    url = cleanedUrl,
                    contextBefore = contextBefore,
                    contextAfter = contextAfter,
                )
            }
            .toList()
    }

    /**
     * Legacy method - extracts just URLs without context.
     * For safety qualification, use extractLinksWithContext() instead.
     */
    private fun extractLinks(content: String): List<String> =
        extractLinksWithContext(content).map { it.url }

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
        var cleaned = url
            // Remove trailing punctuation that might have been captured from surrounding text
            .trimEnd('.', ',', ')', ']', '}', '>', ';', '!')

        // Handle edge case: duplicate URL in fragment (e.g., #gallery(https://example.com#gallery))
        // Extract just the first valid URL if there's duplication
        val fragmentIndex = cleaned.indexOf('#')
        if (fragmentIndex > 0) {
            val fragment = cleaned.substring(fragmentIndex + 1)
            // Check if fragment contains another URL (malformed)
            if (fragment.contains("http://") || fragment.contains("https://")) {
                // Keep only the base URL without the malformed fragment
                cleaned = cleaned.substring(0, fragmentIndex)
            } else {
                // Remove parentheses and other invalid chars from fragment
                val cleanFragment = fragment.replace(Regex("[(){}\\[\\]]"), "")
                if (cleanFragment != fragment) {
                    cleaned = cleaned.substring(0, fragmentIndex) + "#" + cleanFragment
                }
            }
        }

        // Validate with java.net.URI
        return try {
            val uri = java.net.URI(cleaned)
            // Basic validation: must have scheme and host
            if (uri.scheme != null && uri.host != null) {
                cleaned
            } else {
                null
            }
        } catch (e: Exception) {
            // URL is malformed and couldn't be cleaned
            logger.debug { "Failed to parse URL even after cleaning: $cleaned (original: $url)" }
            null
        }
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
    private fun removeTrackingParameters(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val query = uri.query ?: return url

            // List of tracking parameters to remove
            val trackingParams = setOf(
                // UTM parameters (Google Analytics, marketing campaigns)
                "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
                // Social media tracking
                "fbclid", // Facebook click ID
                "gclid", // Google click ID
                "msclkid", // Microsoft click ID
                "twclid", // Twitter click ID
                "li_fat_id", // LinkedIn
                // Email marketing
                "mc_cid", "mc_eid", // Mailchimp
                // General analytics
                "_ga", "_gl", // Google Analytics
                "ref", "source", // Generic referrer tracking
            )

            // Parse query string and filter out tracking params
            val cleanedParams = query.split("&")
                .filter { param ->
                    val key = param.substringBefore("=").lowercase()
                    key !in trackingParams
                }

            // Rebuild URL
            val cleanedQuery = if (cleanedParams.isEmpty()) null else cleanedParams.joinToString("&")

            val rebuiltUrl = buildString {
                append(uri.scheme)
                append("://")
                append(uri.authority)
                append(uri.path ?: "")
                if (cleanedQuery != null) {
                    append("?")
                    append(cleanedQuery)
                }
                uri.fragment?.let {
                    append("#")
                    append(it)
                }
            }

            if (rebuiltUrl != url) {
                logger.debug { "Cleaned tracking params from URL: $url -> $rebuiltUrl" }
            }

            rebuiltUrl
        } catch (e: Exception) {
            // This should not happen as URL was already validated in cleanAndValidateUrl()
            // But handle defensively just in case
            logger.debug { "Failed to parse URL for tracking param removal (already validated): $url - ${e.message}" }
            url // Return original on parse failure
        }
    }
}
