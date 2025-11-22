package com.jervis.service.confluence.processor

import com.jervis.common.client.ITikaClient
import com.jervis.domain.confluence.ConfluenceContentResult
import com.jervis.entity.ConfluencePageDocument
import com.jervis.rag.DocumentToStore
import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeType
import mu.KotlinLogging
import org.jsoup.Jsoup
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Processes Confluence page content for RAG indexing.
 *
 * Responsibilities:
 * - Parse Confluence XHTML storage format
 * - Extract plain text and links
 * - Chunk content using existing ChunkingService pattern
 * - Create TEXT embeddings (not CODE)
 * - Store in vector DB with metadata
 *
 * Link Handling:
 * - Extract all <a> tags from HTML
 * - Classify as internal (same Confluence domain) vs external
 * - Internal links → follow and index recursively
 * - External links → log but don't follow
 * - Relative links → resolve to absolute URLs
 */
@Service
class ConfluenceContentProcessor(
    private val knowledgeService: KnowledgeService,
    private val tikaClient: ITikaClient,
    private val linkSafetyQualifier: com.jervis.service.link.LinkSafetyQualifier,
    private val linkIndexingQueue: com.jervis.service.indexing.LinkIndexingQueue,
) {
    /**
     * Process page content and index into RAG.
     * Returns number of chunks indexed.
     */
    suspend fun processAndIndexPage(
        page: ConfluencePageDocument,
        htmlContent: String,
        baseUrl: String,
    ): ConfluenceContentResult {
        logger.info { "Processing content for page: ${page.title} (${page.pageId})" }

        try {
            // Parse HTML and extract data
            val parsed = parseHtmlContent(page, htmlContent, baseUrl)

            if (parsed.plainText.isBlank()) {
                logger.warn { "Page ${page.pageId} has no text content, skipping indexing" }
                return ConfluenceContentResult(
                    indexedChunks = 0,
                    internalLinks = parsed.internalLinks,
                    externalLinks = parsed.externalLinks,
                    plainText = "",
                )
            }

            val documentToStore =
                DocumentToStore(
                    documentId = "confluence:${page.pageId}",
                    content = parsed.plainText,
                    clientId = page.clientId,
                    projectId = page.projectId,
                    type = KnowledgeType.DOCUMENT,
                    embeddingType = EmbeddingType.TEXT,
                    title = page.title,
                    location = page.url,
                )

            knowledgeService
                .store(com.jervis.rag.StoreRequest(listOf(documentToStore)))

            logger.info {
                "Indexed page ${page.title}, " +
                    "${parsed.internalLinks.size} internal links, ${parsed.externalLinks.size} external links"
            }

            return ConfluenceContentResult(
                indexedChunks = 1,
                internalLinks = parsed.internalLinks,
                externalLinks = parsed.externalLinks,
                plainText = parsed.plainText,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to process page ${page.pageId}" }
            throw e
        }
    }

    /**
     * Parse HTML content and extract plain text + links.
     */
    private suspend fun parseHtmlContent(
        page: ConfluencePageDocument,
        htmlContent: String,
        baseUrl: String,
    ): ParsedContent {
        // SECURITY: Remove tracking pixels and malicious elements BEFORE any processing
        val sanitizedHtml = removeTrackingPixels(htmlContent)
        val doc = Jsoup.parse(sanitizedHtml, baseUrl)

        // Extract all links before cleaning
        // Filter out mailto: and other non-http(s) schemes
        val allLinks =
            doc
                .select("a[href]")
                .mapNotNull { element ->
                    val href = element.attr("abs:href") // Resolves relative URLs
                    when {
                        href.isBlank() -> null
                        href.startsWith("mailto:", ignoreCase = true) -> null
                        href.startsWith("tel:", ignoreCase = true) -> null
                        href.startsWith("javascript:", ignoreCase = true) -> null
                        href.startsWith("data:", ignoreCase = true) -> null
                        else -> href
                    }
                }.distinct()

        // Classify links
        val (internalLinks, externalLinks) = classifyLinks(allLinks, baseUrl)

        // Extract Jira issue links and submit to queue for Jira indexer
        val jiraLinks = internalLinks.filter { isJiraIssueLink(it) }
        if (jiraLinks.isNotEmpty()) {
            logger.info { "Found ${jiraLinks.size} Jira issue links in Confluence page ${page.pageId}, submitting to Jira indexer queue" }
            jiraLinks.forEach { jiraUrl ->
                linkIndexingQueue.submitUrl(
                    url = jiraUrl,
                    clientId = page.clientId,
                    projectId = page.projectId,
                    sourceIndexer = "Confluence",
                    sourceRef = page.pageId,
                )
            }
        }

        // Remove Jira links from internal links (they'll be indexed by Jira indexer)
        val confluenceOnlyLinks = internalLinks.filterNot { isJiraIssueLink(it) }

        // SECURITY: Filter internal links through safety qualifier
        // Prevents following malicious internal Confluence links (rare but possible)
        val safeInternalLinks = filterSafeLinks(confluenceOnlyLinks)
        val blockedInternalCount = confluenceOnlyLinks.size - safeInternalLinks.size
        if (blockedInternalCount > 0) {
            logger.warn {
                "Blocked $blockedInternalCount internal Confluence links " +
                    "(action/tracking/unsubscribe patterns detected)"
            }
        }

        // Extract plain text using Tika (authoritative plain text conversion)
        val plainText =
            try {
                val bytes = sanitizedHtml.toByteArray(Charsets.UTF_8)
                val result =
                    tikaClient.process(
                        com.jervis.common.dto.TikaProcessRequest(
                            source =
                                com.jervis.common.dto.TikaProcessRequest.Source.FileBytes(
                                    fileName = "page.html",
                                    dataBase64 =
                                        java.util.Base64
                                            .getEncoder()
                                            .encodeToString(bytes),
                                ),
                            includeMetadata = false,
                        ),
                    )
                if (result.success && result.plainText.isNotBlank()) {
                    result.plainText
                } else {
                    Jsoup
                        .parse(htmlContent, baseUrl)
                        .text()
                        .trim()
                }
            } catch (e: Exception) {
                logger.warn(e) { "Tika failed to extract plain text for Confluence page, fallback to Jsoup.text()" }
                Jsoup.parse(htmlContent, baseUrl).text().trim()
            }

        return ParsedContent(
            plainText = plainText,
            internalLinks = safeInternalLinks, // Return only safe internal links
            externalLinks = externalLinks,
        )
    }

    /**
     * Classify links as internal (same domain) or external.
     */
    private fun classifyLinks(
        links: List<String>,
        baseUrl: String,
    ): Pair<List<String>, List<String>> {
        val baseDomain = extractDomain(baseUrl)

        val internal = mutableListOf<String>()
        val external = mutableListOf<String>()

        for (link in links) {
            val linkDomain = extractDomain(link)

            if (linkDomain == baseDomain) {
                internal.add(link)
            } else {
                external.add(link)
            }
        }

        return internal to external
    }

    /**
     * Extract domain from URL.
     */
    private fun extractDomain(url: String): String? =
        try {
            java.net
                .URI(url)
                .host
                ?.lowercase()
        } catch (e: Exception) {
            null
        }

    /**
     * Remove tracking pixels and malicious elements from HTML.
     * SECURITY: Prevents tracking pixel activation during indexing.
     *
     * Removes:
     * - 1x1 images (tracking pixels)
     * - Images with tracking/pixel/beacon in URL
     * - Images from known tracking domains
     * - Script tags (shouldn't be in Confluence but defense in depth)
     * - Iframe tags (prevent embedded trackers)
     */
    private fun removeTrackingPixels(html: String): String {
        val doc = Jsoup.parse(html)

        // Remove 1x1 tracking pixels
        doc.select("img[width='1']").remove()
        doc.select("img[height='1']").remove()

        // Remove images with tracking keywords in src
        doc.select("img[src*='tracking'], img[src*='pixel'], img[src*='beacon']").remove()
        doc.select("img[src*='track.'], img[src*='open.'], img[src*='view.']").remove()

        // Remove images from known tracking domains
        doc.select("img[src*='list-manage.com']").remove()
        doc.select("img[src*='sendgrid.net']").remove()
        doc.select("img[src*='mailgun.']").remove()
        doc.select("img[src*='mandrillapp.com']").remove()

        // Remove script tags (defense in depth - shouldn't be in Confluence)
        doc.select("script").remove()

        // Remove iframes (can contain tracking beacons)
        doc.select("iframe").remove()

        logger.debug { "Sanitized HTML: removed tracking elements" }

        return doc.html()
    }

    /**
     * Filter links through safety qualifier to remove action/tracking links.
     * SECURITY: Prevents following internal Confluence links that could trigger actions.
     */
    private suspend fun filterSafeLinks(links: List<String>): List<String> =
        links.filter { link ->
            val result = linkSafetyQualifier.qualifyLink(link)
            when (result.decision) {
                com.jervis.service.link.LinkSafetyQualifier.SafetyResult.Decision.SAFE -> true
                else -> {
                    logger.info { "Blocked internal Confluence link: $link (${result.reason})" }
                    false
                }
            }
        }

    /**
     * Check if URL is a Jira issue link.
     * Jira issue URLs contain /browse/ or /rest/api/.../issue/
     */
    private fun isJiraIssueLink(url: String): Boolean =
        url.contains("/browse/") ||
            (url.contains("/rest/api/") && url.contains("/issue/"))

    private data class ParsedContent(
        val plainText: String,
        val internalLinks: List<String>,
        val externalLinks: List<String>,
    )
}
