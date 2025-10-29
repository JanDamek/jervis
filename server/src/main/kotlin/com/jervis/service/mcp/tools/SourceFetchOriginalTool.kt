package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.repository.mongo.EmailAccountMongoRepository
import com.jervis.service.listener.email.imap.ImapClient
import com.jervis.service.listener.email.state.EmailMessageRepository
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Tool for fetching original source content from URI references.
 *
 * Supports:
 * - email://accountId/messageId - Fetch complete email with all attachments
 * - email://accountId/messageId/attachment/N - Fetch specific attachment
 * - file://path - Fetch file content
 *
 * Use when RAG chunks are insufficient and full original source is needed.
 */
@Service
class SourceFetchOriginalTool(
    private val imapClient: ImapClient,
    private val emailAccountRepository: EmailAccountMongoRepository,
    private val emailMessageRepository: EmailMessageRepository,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.SOURCE_FETCH_ORIGINAL_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.info { "SOURCE_FETCH: Fetching original source from URI" }

        return try {
            val uri = extractUri(taskDescription)
            val content = fetchFromUri(uri)

            ToolResult.success(
                toolName = name.name,
                summary = "Fetched original source: $uri (${content.length} chars)",
                content = content,
            )
        } catch (e: Exception) {
            logger.error(e) { "SOURCE_FETCH: Failed to fetch original source" }
            ToolResult.error(
                output = "Failed to fetch source: ${e.message}",
                message = e.message,
            )
        }
    }

    private fun extractUri(taskDescription: String): String {
        // Extract URI from task description
        // Expected format: "uri: email://..." or just "email://..."
        val uriPattern = Regex("""(email|file)://[^\s]+""")
        return uriPattern.find(taskDescription)?.value
            ?: throw IllegalArgumentException("No valid URI found in task description: $taskDescription")
    }

    private suspend fun fetchFromUri(uri: String): String {
        logger.debug { "Fetching from URI: $uri" }

        return when {
            uri.startsWith("email://") -> fetchEmail(uri)
            uri.startsWith("file://") -> fetchFile(uri)
            else -> throw IllegalArgumentException("Unsupported URI scheme: $uri")
        }
    }

    private suspend fun fetchEmail(uri: String): String {
        // Parse: email://accountId/messageId or email://accountId/messageId/attachment/N
        val parts = uri.removePrefix("email://").split("/")

        if (parts.size < 2) {
            throw IllegalArgumentException("Invalid email URI format: $uri")
        }

        val accountId = ObjectId(parts[0])
        val messageId = parts[1]

        val account =
            emailAccountRepository.findById(accountId)
                ?: throw IllegalArgumentException("Email account not found: $accountId")

        // Find UID from database by messageId
        val emailDoc =
            emailMessageRepository.findByAccountIdAndMessageId(accountId, messageId)
                ?: throw IllegalArgumentException("Email message not found in database: $messageId")

        val uid =
            emailDoc.uid
                ?: throw IllegalArgumentException("Email message has no UID in database: $messageId")

        val message =
            imapClient.fetchMessage(account, uid)
                ?: throw IllegalArgumentException("Email message not found in IMAP: UID=$uid")

        return if (parts.size >= 4 && parts[2] == "attachment") {
            // Fetch specific attachment
            val attachmentIndex =
                parts[3].toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid attachment index: ${parts[3]}")

            if (attachmentIndex >= message.attachments.size) {
                throw IllegalArgumentException("Attachment index $attachmentIndex out of bounds (${message.attachments.size} attachments)")
            }

            val attachment = message.attachments[attachmentIndex]
            buildString {
                appendLine("ATTACHMENT: ${attachment.fileName}")
                appendLine("Content-Type: ${attachment.contentType}")
                appendLine("Size: ${attachment.size} bytes")
                appendLine()
                appendLine("Binary data (${attachment.data.size} bytes)")
                appendLine("Note: Binary content cannot be displayed as text. Use document extraction tools if needed.")
            }
        } else {
            // Fetch complete email
            buildString {
                appendLine("FROM: ${message.from}")
                appendLine("TO: ${message.to}")
                appendLine("SUBJECT: ${message.subject}")
                appendLine("DATE: ${message.receivedAt}")
                appendLine("ATTACHMENTS: ${message.attachments.size}")
                appendLine()
                appendLine("BODY:")
                appendLine(message.content)

                if (message.attachments.isNotEmpty()) {
                    appendLine()
                    appendLine("ATTACHMENT LIST:")
                    message.attachments.forEachIndexed { index, att ->
                        appendLine("  [$index] ${att.fileName} (${att.contentType}, ${att.size} bytes)")
                    }
                }
            }
        }
    }

    private fun fetchFile(uri: String): String {
        // Parse: file://path
        val path = uri.removePrefix("file://")
        val filePath = Paths.get(path)

        if (!Files.exists(filePath)) {
            throw IllegalArgumentException("File not found: $path")
        }

        if (!Files.isRegularFile(filePath)) {
            throw IllegalArgumentException("Path is not a regular file: $path")
        }

        return Files.readString(filePath)
    }
}
