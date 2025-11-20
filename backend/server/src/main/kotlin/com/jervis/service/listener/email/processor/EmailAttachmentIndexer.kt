package com.jervis.service.listener.email.processor

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.service.listener.email.imap.ImapAttachment
import com.jervis.service.listener.email.imap.ImapMessage
import com.jervis.service.rag.RagIndexingService
import com.jervis.service.text.TextChunkingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Service
class EmailAttachmentIndexer(
    private val ragIndexingService: RagIndexingService,
    private val tikaClient: ITikaClient,
    private val textChunkingService: TextChunkingService,
    private val vectorIndexService: com.jervis.service.rag.VectorStoreIndexService,
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
        // Build canonical source ID from attachment content hash to deduplicate across forwards/replies
        val contentHash = sha256(attachment.data)
        val canonicalSourceId = "email-attachment://${accountId.toHexString()}/$contentHash"

        // If already indexed for this project, skip re-indexing identical attachment content
        if (projectId != null) {
            val already =
                kotlin.runCatching {
                    vectorIndexService.existsActive(
                        RagSourceType.EMAIL_ATTACHMENT,
                        canonicalSourceId,
                        projectId,
                    )
                }.getOrDefault(false)
            if (already) {
                logger.info { "Skipped duplicate attachment by hash ${attachment.fileName} ($contentHash)" }
                return
            }
        }

        val processingResult =
            tikaClient.process(
                TikaProcessRequest(
                    source =
                        TikaProcessRequest.Source.FileBytes(
                            fileName = attachment.fileName,
                            dataBase64 = Base64.getEncoder().encodeToString(attachment.data),
                        ),
                    includeMetadata = true,
                ),
            )

        if (!processingResult.success || processingResult.plainText.isBlank()) {
            logger.debug { "No text extracted from attachment ${attachment.fileName}" }
            return
        }

        val chunks = textChunkingService.splitText(processingResult.plainText)
        logger.debug { "Split attachment ${attachment.fileName} into ${chunks.size} chunks" }

        chunks.forEachIndexed { chunkIndex, chunk ->
            ragIndexingService.indexDocument(
                RagDocument(
                    projectId = projectId,
                    clientId = clientId,
                    text = chunk.text(),
                    ragSourceType = RagSourceType.EMAIL_ATTACHMENT,
                    createdAt = message.receivedAt,
                    // Use canonical source ID by attachment content hash
                    sourceUri = canonicalSourceId,
                    // Universal metadata fields
                    from = message.from,
                    subject = message.subject,
                    timestamp = message.receivedAt.toString(),
                    parentRef = message.messageId,
                    chunkId = chunkIndex,
                    chunkOf = chunks.size,
                    fileName = attachment.fileName,
                ),
                ModelTypeEnum.EMBEDDING_TEXT,
            )
        }

        logger.info { "Indexed attachment ${attachment.fileName} with ${chunks.size} chunks" }
    }

    private fun sha256(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
