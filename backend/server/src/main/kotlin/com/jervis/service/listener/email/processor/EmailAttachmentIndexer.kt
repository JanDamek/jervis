package com.jervis.service.listener.email.processor

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.rag.DocumentToStore
import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeType
import com.jervis.service.listener.email.imap.ImapAttachment
import com.jervis.service.listener.email.imap.ImapMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Service
class EmailAttachmentIndexer(
    private val knowledgeService: KnowledgeService,
    private val tikaClient: ITikaClient,
) {
    suspend fun indexAttachments(
        message: ImapMessage,
        accountId: ObjectId,
        clientId: ObjectId,
        projectId: ObjectId?,
    ) = withContext(Dispatchers.IO) {
        logger.debug { "Processing ${message.attachments.size} attachments for email from=${message.from} subject=${message.subject}" }

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
        val contentHash = sha256(attachment.data)
        val canonicalSourceId = "email-attachment://${accountId.toHexString()}/$contentHash"

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

        val documentToStore =
            DocumentToStore(
                documentId = canonicalSourceId,
                content = processingResult.plainText,
                clientId = clientId,
                projectId = projectId,
                type = KnowledgeType.DOCUMENT,
                embeddingType = EmbeddingType.TEXT,
                title = attachment.fileName,
                location = "email:${message.from}/${message.subject}",
            )

        knowledgeService
            .store(com.jervis.rag.StoreRequest(listOf(documentToStore)))

        logger.info { "Indexed attachment ${attachment.fileName} for email from=${message.from} subject=${message.subject}" }
    }

    private fun sha256(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
