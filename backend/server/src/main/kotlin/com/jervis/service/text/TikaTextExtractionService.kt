package com.jervis.service.text

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.common.rpc.withRpcRetry
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
    private val reconnectHandler: com.jervis.configuration.RpcReconnectHandler,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun extractPlainText(
        content: String,
        fileName: String = "content.html",
    ): String {
        if (content.isBlank()) {
            return content
        }

        val resolvedFileName = resolveFileName(content, fileName)
        logger.debug {
            "Processing content through Tika (${content.length} chars), fileName=$fileName resolvedFileName=$resolvedFileName"
        }

        return try {
            val request =
                TikaProcessRequest(
                    source =
                        TikaProcessRequest.Source.FileBytes(
                            fileName = resolvedFileName,
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
                val originalLength = content.length
                val reduction =
                    if (originalLength > 0) {
                        ((1.0 - plainText.length.toDouble() / originalLength) * 100).toInt()
                    } else {
                        0
                    }
                logger.info {
                    "Tika extraction successful: $originalLength chars → ${plainText.length} chars " +
                        "($reduction% reduction)"
                }

                if (plainText.isBlank() && content.isNotBlank()) {
                    logger.warn { "Tika returned empty text for non-empty content, returning original content" }
                    content
                } else {
                    plainText
                }
            } else {
                logger.warn { "Tika extraction failed: ${result.errorMessage}, returning original content" }
                content
            }
        } catch (e: Exception) {
            logger.error(e) { "Tika service error, returning original content" }
            content
        }
    }

    private fun resolveFileName(
        content: String,
        fileName: String,
    ): String {
        val trimmed = fileName.trim()
        if (trimmed.isNotEmpty() && !isGenericContentName(trimmed)) {
            return trimmed
        }
        val sample = content.take(4096).lowercase()
        return when {
            looksLikeHtml(sample) -> "content.html"
            looksLikeXml(sample) -> "content.xml"
            else -> "content.txt"
        }
    }

    private fun isGenericContentName(fileName: String): Boolean =
        fileName.equals("content.html", ignoreCase = true) ||
            fileName.equals("content.xml", ignoreCase = true) ||
            fileName.equals("content.txt", ignoreCase = true) ||
            fileName.equals("content.bin", ignoreCase = true)

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
