package com.jervis.koog.tools.external

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.jervis.entity.TaskDocument
import com.jervis.service.email.Attachment
import com.jervis.service.email.Email
import com.jervis.service.email.EmailFilters
import com.jervis.service.email.EmailService
import com.jervis.service.email.EmailThread
import kotlinx.serialization.Serializable
import mu.KotlinLogging

/**
 * READ-ONLY Email tools for context lookup.
 * Used by KoogQualifierAgent to enrich context when indexing email threads or mentioned emails.
 */
@LLMDescription("Read-only Email operations for context lookup and enrichment")
class EmailReadTools(
    private val task: TaskDocument,
    private val emailService: EmailService,
) : ToolSet {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription("Search emails by query with optional filters. Use to find relevant emails by keywords, sender, subject, etc.")
    suspend fun searchEmails(
        @LLMDescription("Search query text")
        query: String,
        @LLMDescription("Filter by sender email (optional)")
        from: String? = null,
        @LLMDescription("Filter by subject keywords (optional)")
        subject: String? = null,
        @LLMDescription("Max results to return")
        maxResults: Int = 20,
    ): EmailSearchResult =
        try {
            logger.info { "EMAIL_SEARCH: query='$query', from=$from, subject=$subject, maxResults=$maxResults" }
            val filters = EmailFilters(from = from, subject = subject)
            val emails = emailService.searchEmails(task.clientId, query, filters, maxResults)
            EmailSearchResult(
                success = true,
                emails = emails,
                totalFound = emails.size,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to search emails" }
            EmailSearchResult(
                success = false,
                emails = emptyList(),
                totalFound = 0,
                error = e.message ?: "Unknown error",
            )
        }

    @Tool
    @LLMDescription("Get specific email by ID. Use to retrieve full email details including body and attachments.")
    suspend fun getEmail(
        @LLMDescription("Email ID")
        emailId: String,
    ): EmailResult =
        try {
            logger.info { "EMAIL_GET: emailId=$emailId" }
            val email = emailService.getEmail(task.clientId, emailId)
            EmailResult(
                success = true,
                email = email,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get email: $emailId" }
            EmailResult(
                success = false,
                email = null,
                error = e.message ?: "Unknown error",
            )
        }

    @Tool
    @LLMDescription("Get email thread by ID. Use to see full conversation history.")
    suspend fun getThread(
        @LLMDescription("Thread ID")
        threadId: String,
    ): EmailThreadResult =
        try {
            logger.info { "EMAIL_GET_THREAD: threadId=$threadId" }
            val thread = emailService.getThread(task.clientId, threadId)
            EmailThreadResult(
                success = true,
                thread = thread,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get email thread: $threadId" }
            EmailThreadResult(
                success = false,
                thread = null,
                error = e.message ?: "Unknown error",
            )
        }

    @Tool
    @LLMDescription("Get email attachment by ID. Use to download and analyze attachments.")
    suspend fun getAttachment(
        @LLMDescription("Attachment ID")
        attachmentId: String,
    ): AttachmentResult =
        try {
            logger.info { "EMAIL_GET_ATTACHMENT: attachmentId=$attachmentId" }
            val attachment = emailService.getAttachment(task.clientId, attachmentId)
            AttachmentResult(
                success = true,
                attachment = attachment,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get email attachment: $attachmentId" }
            AttachmentResult(
                success = false,
                attachment = null,
                error = e.message ?: "Unknown error",
            )
        }
}

@Serializable
data class EmailSearchResult(
    val success: Boolean,
    val emails: List<Email>,
    val totalFound: Int,
    val error: String? = null,
)

@Serializable
data class EmailResult(
    val success: Boolean,
    val email: Email?,
    val error: String? = null,
)

@Serializable
data class EmailThreadResult(
    val success: Boolean,
    val thread: EmailThread?,
    val error: String? = null,
)

@Serializable
data class AttachmentResult(
    val success: Boolean,
    val attachment: Attachment?,
    val error: String? = null,
)
