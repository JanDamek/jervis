package com.jervis.service.listener.email.processor

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.service.listener.email.imap.ImapMessage
import com.jervis.service.rag.RagIndexingService
import com.jervis.service.text.TextChunkingService
import com.jervis.service.text.TextNormalizationService
import dev.langchain4j.data.segment.TextSegment
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Service
class EmailContentIndexer(
    private val ragIndexingService: RagIndexingService,
    private val textChunkingService: TextChunkingService,
    private val tikaClient: ITikaClient,
    private val textNormalizationService: TextNormalizationService,
) {
    suspend fun indexEmailContent(
        message: ImapMessage,
        accountId: ObjectId,
        clientId: ObjectId,
        projectId: ObjectId?,
    ): String? {
        logger.info { "Indexing email content ${message.messageId}" }

        return runCatching {
            val plainText = extractPlainText(message.content)
            val normalizedText = textNormalizationService.normalize(plainText)
            val chunks = splitEmailContent(normalizedText)
            logger.debug { "Split email ${message.messageId} into ${chunks.size} chunks" }

            var firstDocumentId: String? = null
            chunks.forEachIndexed { index, chunk ->
                val docId =
                    storeChunkWithEmbedding(
                        chunk = chunk,
                        message = message,
                        accountId = accountId,
                        clientId = clientId,
                        projectId = projectId,
                        chunkIndex = index,
                        totalChunks = chunks.size,
                    )
                if (index == 0) {
                    firstDocumentId = docId
                }
            }

            logger.info { "Indexed email body ${message.messageId} with ${chunks.size} chunks" }
            firstDocumentId
        }.onFailure { e ->
            logger.error(e) { "Failed to index email content ${message.messageId}" }
        }.getOrNull()
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
                val linksSection = linksInfo.joinToString("\n") { (url, text) ->
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
    private fun extractLinksWithText(htmlContent: String): List<Pair<String, String>> {
        return try {
            val doc = org.jsoup.Jsoup.parse(htmlContent)
            doc.select("a[href]").mapNotNull { element ->
                val href = element.attr("abs:href").takeIf { it.isNotBlank() } ?: element.attr("href")
                val text = element.text().trim()
                if (href.isNotBlank() && href.startsWith("http")) {
                    href to (text.ifBlank { href })
                } else null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract links from HTML" }
            emptyList()
        }
    }

    private fun splitEmailContent(content: String): List<TextSegment> = textChunkingService.splitText(content)

    private suspend fun storeChunkWithEmbedding(
        chunk: TextSegment,
        message: ImapMessage,
        accountId: ObjectId,
        clientId: ObjectId,
        projectId: ObjectId?,
        chunkIndex: Int,
        totalChunks: Int,
    ): String {
        val document =
            RagDocument(
                projectId = projectId,
                clientId = clientId,
                text = chunk.text(),
                ragSourceType = RagSourceType.EMAIL,
                createdAt = message.receivedAt,
                sourceUri = "email://${accountId.toHexString()}/${message.messageId}",
                from = message.from,
                subject = message.subject,
                timestamp = message.receivedAt.toString(),
                parentRef = message.messageId,
                chunkId = chunkIndex,
                chunkOf = totalChunks,
            )

        return ragIndexingService
            .indexDocument(document, ModelTypeEnum.EMBEDDING_TEXT)
            .getOrThrow()
            .vectorStoreId
    }
}
