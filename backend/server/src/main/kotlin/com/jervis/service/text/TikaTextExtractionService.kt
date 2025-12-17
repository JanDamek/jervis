package com.jervis.service.text

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Service for cleaning HTML/XML content and extracting plain text using Tika.
 *
 * Purpose:
 * - Remove HTML/XML formatting from content before sending to LLM
 * - Extract clean, readable text from Confluence XML, email HTML, etc.
 * - Reduce token waste on syntax and formatting
 *
 * Usage:
 * - Call before creating PendingTask to ensure content is clean
 * - Automatically detects if content contains HTML/XML and processes it
 */
@Service
class TikaTextExtractionService(
    private val tikaClient: ITikaClient,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Extract plain text from potentially HTML/XML content.
     *
     * Detects if content contains HTML/XML tags and processes it through Tika.
     * If content is already plain text, returns it unchanged.
     *
     * @param content Raw content (may contain HTML/XML)
     * @param fileName Optional filename for better Tika processing (e.g., "content.html")
     * @return Clean plain text suitable for LLM processing
     */
    suspend fun extractPlainText(
        content: String,
        fileName: String = "content.html",
    ): String {
        if (content.isBlank()) {
            return content
        }

        // Quick heuristic: if content looks like HTML/XML, process it
        if (!looksLikeHtmlOrXml(content)) {
            logger.debug { "Content does not appear to be HTML/XML, returning as-is" }
            return content
        }

        logger.debug { "Content appears to be HTML/XML (${content.length} chars), processing through Tika" }

        return try {
            val request =
                TikaProcessRequest(
                    source =
                        TikaProcessRequest.Source.FileBytes(
                            fileName = fileName,
                            dataBase64 =
                                java.util.Base64
                                    .getEncoder()
                                    .encodeToString(content.toByteArray()),
                        ),
                    includeMetadata = false,
                )

            val result = tikaClient.process(request)

            if (result.success) {
                val plainText = result.plainText.trim()
                logger.info {
                    "Tika extraction successful: ${content.length} chars → ${plainText.length} chars " +
                        "(${((1.0 - plainText.length.toDouble() / content.length) * 100).toInt()}% reduction)"
                }
                plainText
            } else {
                logger.warn { "Tika extraction failed: ${result.errorMessage}, returning original content" }
                content
            }
        } catch (e: Exception) {
            logger.error(e) { "Tika service error, returning original content" }
            content
        }
    }

    /**
     * Heuristic to detect if content contains HTML/XML tags.
     *
     * Checks for:
     * - HTML tags (<div>, <p>, <span>, <a>, etc.)
     * - XML tags (ac:link, ri:page, etc.)
     * - High density of < and > characters
     */
    private fun looksLikeHtmlOrXml(content: String): Boolean {
        // Common HTML/XML indicators
        val htmlXmlPatterns =
            listOf(
                "<div",
                "<p>",
                "<span",
                "<a ",
                "<br",
                "<table",
                "<ul",
                "<li",
                "<h1",
                "<h2",
                "<h3",
                "ac:link",
                "ri:page",
                "ri:content-title",
                "<ac:",
                "<ri:",
                "xmlns",
                "<!DOCTYPE",
                "<html",
            )

        val lowerContent = content.lowercase()
        if (htmlXmlPatterns.any { lowerContent.contains(it) }) {
            return true
        }

        // Check tag density: if > 5% of characters are < or >, likely HTML/XML
        val tagCharCount = content.count { it == '<' || it == '>' }
        val tagDensity = tagCharCount.toDouble() / content.length
        if (tagDensity > 0.05) {
            return true
        }

        return false
    }
}
