package com.jervis.service.listener.email.processor

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.EmbeddingType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.document.TikaDocumentProcessor
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.listener.email.imap.ImapAttachment
import com.jervis.service.listener.email.imap.ImapMessage
import com.jervis.service.text.TextChunkingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream

private val logger = KotlinLogging.logger {}

@Service
class EmailAttachmentIndexer(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val tikaDocumentProcessor: TikaDocumentProcessor,
    private val textChunkingService: TextChunkingService,
) {
    suspend fun indexAttachments(
        message: ImapMessage,
        accountId: ObjectId,
        clientId: ObjectId,
        projectId: ObjectId?,
    ) = withContext(Dispatchers.IO) {
        logger.debug { "Processing ${message.attachments.size} attachments for email ${message.messageId}" }

        message.attachments.forEachIndexed { index, attachment ->
            try {
                indexAttachment(
                    message = message,
                    attachment = attachment,
                    attachmentIndex = index,
                    accountId = accountId,
                    clientId = clientId,
                    projectId = projectId,
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to index attachment ${attachment.fileName}" }
            }
        }
    }

    private suspend fun indexAttachment(
        message: ImapMessage,
        attachment: ImapAttachment,
        attachmentIndex: Int,
        accountId: ObjectId,
        clientId: ObjectId,
        projectId: ObjectId?,
    ) {
        val processingResult =
            tikaDocumentProcessor.processDocumentStream(
                inputStream = ByteArrayInputStream(attachment.data),
                fileName = attachment.fileName,
                sourceLocation =
                    TikaDocumentProcessor.SourceLocation(
                        documentPath = "email://${accountId.toHexString()}/${message.messageId}/attachment/$attachmentIndex",
                    ),
            )

        if (!processingResult.success || processingResult.plainText.isBlank()) {
            logger.debug { "No text extracted from attachment ${attachment.fileName}" }
            return
        }

        val chunks = textChunkingService.splitText(processingResult.plainText)
        logger.debug { "Split attachment ${attachment.fileName} into ${chunks.size} chunks" }

        chunks.forEachIndexed { chunkIndex, chunk ->
            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, chunk.text())

            vectorStorage.store(
                EmbeddingType.EMBEDDING_TEXT,
                RagDocument(
                    projectId = projectId,
                    clientId = clientId,
                    summary = chunk.text(),
                    ragSourceType = RagSourceType.EMAIL_ATTACHMENT,
                    createdAt = message.receivedAt,
                    sourceUri = "email://${accountId.toHexString()}/${message.messageId}/attachment/$attachmentIndex",
                    // Universal metadata fields
                    from = message.from,
                    subject = message.subject,
                    timestamp = message.receivedAt.toString(),
                    parentRef = message.messageId,
                    indexInParent = attachmentIndex,
                    totalSiblings = message.attachments.size,
                    contentType = attachment.contentType,
                    fileName = attachment.fileName,
                    // Chunking
                    chunkId = chunkIndex,
                    chunkOf = chunks.size,
                ),
                embedding,
            )
        }

        logger.info { "Indexed attachment ${attachment.fileName} with ${chunks.size} chunks" }
    }
}
