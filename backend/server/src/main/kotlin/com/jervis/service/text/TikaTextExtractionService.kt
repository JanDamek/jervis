package com.jervis.service.text

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.entity.PendingTaskDocument
import com.jervis.service.background.PendingTaskService
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
    private val pendingTaskService: PendingTaskService,
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
     * Extract plain text from binary data (downloaded files, PDFs, images, etc.).
     *
     * Used for processing downloaded content where format is unknown.
     *
     * @param data Binary data (PDF, HTML, Word doc, image, etc.)
     * @param fileName Filename for Tika to detect format
     * @return Clean plain text, or empty string on failure
     */
    suspend fun extractPlainTextFromBytes(
        data: ByteArray,
        fileName: String,
    ): String {
        if (data.isEmpty()) {
            return ""
        }

        logger.debug { "Processing binary data (${data.size} bytes) as $fileName through Tika" }

        return try {
            val request =
                TikaProcessRequest(
                    source =
                        TikaProcessRequest.Source.FileBytes(
                            fileName = fileName,
                            dataBase64 =
                                java.util.Base64
                                    .getEncoder()
                                    .encodeToString(data),
                        ),
                    includeMetadata = false,
                )

            val result = tikaClient.process(request)

            if (result.success) {
                val plainText = result.plainText.trim()
                logger.info {
                    "Tika extraction from bytes successful: ${data.size} bytes → ${plainText.length} chars"
                }
                plainText
            } else {
                logger.warn { "Tika extraction from bytes failed: ${result.errorMessage}" }
                ""
            }
        } catch (e: Exception) {
            logger.error(e) { "Tika service error for binary data, returning empty" }
            ""
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

    /**
     * Ensures task content is clean from excessive HTML/XML tags.
     *
     * If content contains more than 5 HTML tags, processes it through Tika
     * and updates the task in database with cleaned content.
     *
     * This is a safety net for cases where continuous indexers missed cleaning.
     */
    suspend fun ensureCleanContent(task: PendingTaskDocument): PendingTaskDocument {
        val htmlTagCount = countHtmlTags(task.content)

        if (htmlTagCount <= 5) {
            return task
        }

        logger.warn {
            "CONTENT_NEEDS_CLEANING: taskId=${task.id} htmlTags=$htmlTagCount - processing through Tika"
        }

        val cleanedContent =
            extractPlainText(
                content = task.content,
                fileName = "task-${task.id}.html",
            )

        val cleanedTask = task.copy(content = cleanedContent)

        // Update task in database with cleaned content
        pendingTaskService.updateTaskContent(task.id, cleanedContent)

        logger.info {
            "CONTENT_CLEANED: taskId=${task.id} before=${task.content.length} after=${cleanedContent.length} " +
                "reduction=${((1.0 - cleanedContent.length.toDouble() / task.content.length) * 100).toInt()}%"
        }

        return cleanedTask
    }

    /**
     * Counts HTML/XML tags in content.
     * Simple heuristic: count occurrences of < followed by letter or /
     */
    private fun countHtmlTags(content: String): Int {
        var count = 0
        var i = 0
        while (i < content.length - 1) {
            if (content[i] == '<') {
                val next = content[i + 1]
                if (next.isLetter() || next == '/') {
                    count++
                }
            }
            i++
        }
        return count
    }
}
