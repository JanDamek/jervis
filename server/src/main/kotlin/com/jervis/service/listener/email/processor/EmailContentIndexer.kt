package com.jervis.service.listener.email.processor

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.service.listener.email.imap.ImapMessage
import com.jervis.service.rag.RagIndexingService
import com.jervis.service.text.TextChunkingService
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
            val chunks = splitEmailContent(plainText)
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
            if (res.success && res.plainText.isNotBlank()) res.plainText else content
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract plain text with Tika, using original content" }
            content
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
