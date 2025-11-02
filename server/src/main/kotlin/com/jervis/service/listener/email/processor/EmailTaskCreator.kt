package com.jervis.service.listener.email.processor

import com.jervis.common.client.ITikaClient
import com.jervis.domain.task.PendingTaskTypeEnum
import com.jervis.service.background.PendingTaskService
import com.jervis.service.listener.email.imap.ImapMessage
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class EmailTaskCreator(
    private val pendingTaskService: PendingTaskService,
    private val tikaClient: ITikaClient,
) {
    companion object {
        private const val MAX_CONTENT_SIZE = 15 * 1024 * 1024
        private val URL_PATTERN = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
    }

    suspend fun createTaskForEmail(
        message: ImapMessage,
        accountId: ObjectId,
        clientId: ObjectId,
        projectId: ObjectId?,
    ) {
        runCatching {
            val cleanBody = cleanEmailBody(message.content)
            val combinedContent = buildCombinedContent(message)

            // Build context map for prompt template substitution (used by qualifier and orchestrator)
            val context =
                mapOf(
                    "from" to message.from,
                    "to" to message.to,
                    "subject" to message.subject,
                    "date" to message.receivedAt.toString(),
                    "body" to cleanBody,
                )

            pendingTaskService.createTask(
                taskType = PendingTaskTypeEnum.EMAIL_PROCESSING,
                content = combinedContent,
                projectId = projectId,
                clientId = clientId,
                needsQualification = true, // Qualifier can create simple actions directly
                context = context,
            )

            logger.info { "Created pending task for email ${message.messageId} with ${combinedContent.length} chars" }
        }.onFailure { e ->
            logger.error(e) { "Failed to create pending task for email ${message.messageId}" }
        }
    }

    private suspend fun buildCombinedContent(message: ImapMessage): String {
        // Clean email body: HTML→plain text, remove URLs
        val cleanBody = cleanEmailBody(message.content)

        val content =
            buildString {
                appendLine("FROM: ${message.from}")
                appendLine("TO: ${message.to}")
                appendLine("SUBJECT: ${message.subject}")
                appendLine("DATE: ${message.receivedAt}")
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

        return if (content.length > MAX_CONTENT_SIZE) {
            logger.warn { "Email content truncated from ${content.length} to $MAX_CONTENT_SIZE bytes" }
            content.take(MAX_CONTENT_SIZE) + "\n\n[Content truncated - use source_fetch_original for full email]"
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

        // Normalize whitespace
        return withoutUrls
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }
}
