package com.jervis.service.listener.email.processor

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.EmbeddingType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.document.TikaDocumentProcessor
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.listener.email.imap.ImapMessage
import com.jervis.service.text.TextChunkingService
import dev.langchain4j.data.segment.TextSegment
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream

private val logger = KotlinLogging.logger {}

@Service
class EmailContentIndexer(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val textChunkingService: TextChunkingService,
    private val tikaDocumentProcessor: TikaDocumentProcessor,
) {
    suspend fun indexEmailContent(
        message: ImapMessage,
        accountId: ObjectId,
        clientId: ObjectId,
        projectId: ObjectId?,
    ) {
        logger.info { "Indexing email content ${message.messageId}" }

        runCatching {
            val plainText = extractPlainText(message.content)
            val chunks = splitEmailContent(plainText)
            logger.debug { "Split email ${message.messageId} into ${chunks.size} chunks" }

            chunks.forEachIndexed { index, chunk ->
                storeChunkWithEmbedding(
                    chunk = chunk,
                    message = message,
                    accountId = accountId,
                    clientId = clientId,
                    projectId = projectId,
                    chunkIndex = index,
                    totalChunks = chunks.size,
                )
            }

            logger.info { "Indexed email body ${message.messageId} with ${chunks.size} chunks" }
        }.onFailure { e ->
            logger.error(e) { "Failed to index email content ${message.messageId}" }
            throw e
        }
    }

    private suspend fun extractPlainText(content: String): String =
        runCatching {
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            ByteArrayInputStream(contentBytes).use { inputStream ->
                val result =
                    tikaDocumentProcessor.processDocumentStream(
                        inputStream = inputStream,
                        fileName = "email.html",
                    )
                result.plainText.takeIf { it.isNotBlank() } ?: content
            }
        }.getOrElse { e ->
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
    ) {
        val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, chunk.text())

        vectorStorage.store(
            EmbeddingType.EMBEDDING_TEXT,
            RagDocument(
                projectId = projectId,
                clientId = clientId,
                summary = chunk.text(),
                ragSourceType = RagSourceType.EMAIL,
                createdAt = message.receivedAt,
                sourceUri = "email://${accountId.toHexString()}/${message.messageId}",
                // Universal metadata fields
                from = message.from,
                subject = message.subject,
                timestamp = message.receivedAt.toString(),
                parentRef = message.messageId,
                totalSiblings = message.attachments.size,
                contentType = "text/html",
                // Chunking
                chunkId = chunkIndex,
                chunkOf = totalChunks,
            ),
            embedding,
        )
    }
}
