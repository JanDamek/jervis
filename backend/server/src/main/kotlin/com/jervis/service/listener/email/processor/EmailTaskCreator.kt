package com.jervis.service.listener.email.processor

import com.jervis.common.client.ITikaClient
import com.jervis.domain.task.PendingTaskTypeEnum
import com.jervis.service.background.PendingTaskService
import com.jervis.service.listener.email.imap.ImapMessage
import com.jervis.service.text.TextNormalizationService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class EmailTaskCreator(
    private val pendingTaskService: PendingTaskService,
    private val tikaClient: ITikaClient,
    private val textNormalizationService: TextNormalizationService,
) {
    companion object {
        private const val MAX_CONTENT_BYTES = 12 * 1024 * 1024 // 12 MiB headroom to stay under Mongo 16MB limit
        private val URL_PATTERN = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
    }

    suspend fun createTaskForEmail(
        message: ImapMessage,
        accountId: ObjectId,
        clientId: ObjectId,
        projectId: ObjectId?,
    ) {
        runCatching {
            val combinedContent = buildCombinedContent(message, accountId)

            pendingTaskService.createTask(
                taskType = PendingTaskTypeEnum.EMAIL_PROCESSING,
                content = combinedContent,
                projectId = projectId,
                clientId = clientId,
                sourceUri = "email://${accountId.toHexString()}/${message.messageId}",
            )

            logger.info { "Created pending task for email ${message.messageId} with ${combinedContent.length} chars" }
        }.onFailure { e ->
            logger.error(e) { "Failed to create pending task for email ${message.messageId}" }
        }
    }

    private suspend fun buildCombinedContent(
        message: ImapMessage,
        accountId: ObjectId,
    ): String {
        // Clean email body: HTML→plain text, remove URLs, normalize
        val cleanBody = cleanEmailBody(message.content)

        // Everything in content - simple and clear
        val content =
            buildString {
                appendLine("Email Processing Required")
                appendLine()
                appendLine("FROM: ${message.from}")
                appendLine("TO: ${message.to}")
                appendLine("SUBJECT: ${message.subject}")
                appendLine("DATE: ${message.receivedAt}")
                appendLine("MESSAGE ID: ${message.messageId}")
                appendLine("SOURCE: email://${accountId.toHexString()}/${message.messageId}")
                appendLine()
                appendLine("BODY:")
                appendLine(cleanBody)

                if (message.attachments.isNotEmpty()) {
                    appendLine()
                    appendLine("ATTACHMENTS (${message.attachments.size}):")
                    message.attachments.forEachIndexed { index, attachment ->
                        appendLine("[$index] ${attachment.fileName} (${attachment.contentType}, ${attachment.size} bytes)")
                    }
                    appendLine()
                    appendLine("Note: Attachment content indexed separately. Use source_fetch_original if full content needed.")
                }
            }

        return if (content.length > MAX_CONTENT_BYTES) {
            logger.warn { "Email content truncated from ${content.length} to $MAX_CONTENT_BYTES bytes" }
            content.take(MAX_CONTENT_BYTES) + "\n\n[Content truncated - use source_fetch_original for full email]"
        } else {
            content
        }
    }

    /**
     * Cleans email body using Tika:
     * - Converts HTML/any format to plain text
     * - Removes URLs (they are indexed separately via LinkIndexer)
     * - Normalizes whitespace
     */
    private suspend fun cleanEmailBody(rawContent: String): String {
        // Use Tika to extract plain text from HTML or any other format
        val plainText: String =
            try {
                val bytes = rawContent.toByteArray()
                val result =
                    tikaClient.process(
                        com.jervis.common.dto.TikaProcessRequest(
                            source =
                                com.jervis.common.dto.TikaProcessRequest.Source.FileBytes(
                                    fileName = "email-body.html",
                                    dataBase64 =
                                        java.util.Base64
                                            .getEncoder()
                                            .encodeToString(bytes),
                                ),
                            includeMetadata = false,
                        ),
                    )
                if (result.success) result.plainText else rawContent
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse email body with Tika, using raw content" }
                rawContent
            }

        // Remove URLs (they're indexed separately)
        val withoutUrls = URL_PATTERN.replace(plainText, "[URL]")

        // Normalize whitespace using centralized service
        return textNormalizationService.normalize(withoutUrls)
    }
}
