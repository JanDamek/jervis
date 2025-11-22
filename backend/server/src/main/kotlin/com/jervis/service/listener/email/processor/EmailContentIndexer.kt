package com.jervis.service.listener.email.processor

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.rag.DocumentToStore
import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeType
import com.jervis.service.listener.email.imap.ImapMessage
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Service
class EmailContentIndexer(
    private val knowledgeService: KnowledgeService,
    private val tikaClient: ITikaClient,
) {
    /**
     * Public helper to get normalized plain text for an email body (HTML → text).
     */
    suspend fun extractAndNormalizeText(rawHtml: String): String = extractPlainText(rawHtml)

    suspend fun indexEmailContent(
        message: ImapMessage,
        accountId: ObjectId,
        clientId: ObjectId,
        projectId: ObjectId?,
        canonicalSourceId: String,
    ): String {
        logger.info { "Indexing email content from=${message.from} subject=${message.subject}" }

        return runCatching {
            val plainText = extractPlainText(message.content)

            val documentToStore =
                DocumentToStore(
                    documentId = canonicalSourceId,
                    content = plainText,
                    clientId = clientId,
                    projectId = projectId,
                    type = KnowledgeType.DOCUMENT,
                    embeddingType = EmbeddingType.TEXT,
                    title = message.subject,
                    location = "email:${message.from}",
                )

            knowledgeService
                .store(com.jervis.rag.StoreRequest(listOf(documentToStore)))

            logger.info { "Indexed email body from=${message.from} subject=${message.subject}" }
            canonicalSourceId
        }.onFailure { e ->
            logger.error(e) { "Failed to index email content from=${message.from} subject=${message.subject}" }
            throw e
        }.getOrThrow()
    }

    private suspend fun extractPlainText(content: String): String =
        try {
            // First extract links from HTML before Tika strips them
            val linksInfo = extractLinksWithText(content)

            val contentBytes = content.toByteArray(Charsets.UTF_8)
            val res =
                tikaClient.process(
                    TikaProcessRequest(
                        source =
                            TikaProcessRequest.Source.FileBytes(
                                fileName = "email.html",
                                dataBase64 = Base64.getEncoder().encodeToString(contentBytes),
                            ),
                        includeMetadata = false,
                    ),
                )

            val plainText = if (res.success && res.plainText.isNotBlank()) res.plainText else content

            // Append link information to plain text for better searchability
            // Format: "Link: [anchor text] - URL"
            if (linksInfo.isNotEmpty()) {
                val linksSection =
                    linksInfo.joinToString("\n") { (url, text) ->
                        "Link: $text - $url"
                    }
                "$plainText\n\n--- Links in this email ---\n$linksSection"
            } else {
                plainText
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract plain text with Tika, using original content" }
            content
        }

    /**
     * Extract links with their anchor text from HTML.
     * Returns list of (URL, anchor text) pairs.
     */
    private fun extractLinksWithText(htmlContent: String): List<Pair<String, String>> =
        try {
            val doc = org.jsoup.Jsoup.parse(htmlContent)
            doc.select("a[href]").mapNotNull { element ->
                val href = element.attr("abs:href").takeIf { it.isNotBlank() } ?: element.attr("href")
                val text = element.text().trim()
                if (href.isNotBlank() && href.startsWith("http")) {
                    href to (text.ifBlank { href })
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract links from HTML" }
            emptyList()
        }
}
