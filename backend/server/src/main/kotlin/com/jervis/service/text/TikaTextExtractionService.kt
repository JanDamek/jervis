package com.jervis.service.text

import mu.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import org.springframework.stereotype.Service

/**
 * Service for cleaning HTML/XML content and extracting plain text.
 *
 * Purpose:
 * - Remove HTML/XML formatting from content before sending to LLM
 * - Extract clean, readable text from Confluence XML, email HTML, etc.
 * - Reduce token waste on syntax and formatting
 *
 * Uses Jsoup for HTML parsing (no external service dependency).
 */
@Service
class TikaTextExtractionService {
    private val logger = KotlinLogging.logger {}

    suspend fun extractPlainText(
        content: String,
        fileName: String = "content.html",
    ): String {
        if (content.isBlank()) {
            return content
        }

        val sample = content.take(4096).lowercase()
        val isHtmlOrXml = looksLikeHtml(sample) || looksLikeXml(sample)

        if (!isHtmlOrXml) {
            return content
        }

        return try {
            val doc = Jsoup.parse(content)
            // Remove script, style, noscript elements
            doc.select("script, style, noscript").remove()

            val plainText = doc.text().trim()
            val originalLength = content.length
            val reduction =
                if (originalLength > 0) {
                    ((1.0 - plainText.length.toDouble() / originalLength) * 100).toInt()
                } else {
                    0
                }
            logger.info {
                "HTML extraction successful: $originalLength chars → ${plainText.length} chars " +
                    "($reduction% reduction)"
            }

            if (plainText.isBlank() && content.isNotBlank()) {
                logger.warn { "HTML parsing returned empty text for non-empty content, returning original" }
                content
            } else {
                plainText
            }
        } catch (e: Exception) {
            logger.error(e) { "HTML parsing error, returning original content" }
            content
        }
    }

    private fun looksLikeHtml(sample: String): Boolean =
        sample.contains("<!doctype") ||
            sample.contains("<html") ||
            sample.contains("<body") ||
            sample.contains("</") ||
            Regex("<\\s*[a-zA-Z][^>]*>").containsMatchIn(sample)

    private fun looksLikeXml(sample: String): Boolean =
        sample.contains("<?xml") ||
            (sample.contains("</") && sample.contains(">"))
}
