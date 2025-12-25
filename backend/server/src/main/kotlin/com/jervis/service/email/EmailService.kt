package com.jervis.service.email

import com.jervis.types.ClientId
import kotlinx.serialization.Serializable

/**
 * Email integration service (READ + WRITE operations).
 * Currently a TODO/mock interface - implementation pending.
 */
interface EmailService {
    // ==================== READ OPERATIONS ====================

    /**
     * Search emails by query with filters.
     */
    suspend fun searchEmails(
        clientId: ClientId,
        query: String,
        filters: EmailFilters,
        maxResults: Int = 20,
    ): List<Email>

    /**
     * Get specific email by ID.
     */
    suspend fun getEmail(
        clientId: ClientId,
        emailId: String,
    ): Email

    /**
     * Get email thread by ID.
     */
    suspend fun getThread(
        clientId: ClientId,
        threadId: String,
    ): EmailThread

    /**
     * Get attachment by ID.
     */
    suspend fun getAttachment(
        clientId: ClientId,
        attachmentId: String,
    ): Attachment

    // ==================== WRITE OPERATIONS (Future) ====================

    /**
     * Draft email (does not send).
     * TODO: Implement when needed by LIFT_UP agent
     */
    suspend fun draftEmail(
        clientId: ClientId,
        request: DraftEmailRequest,
    ): EmailDraft

    /**
     * Send drafted email.
     * TODO: Implement when needed by LIFT_UP agent
     * IMPORTANT: Requires user confirmation before sending
     */
    suspend fun sendEmail(
        clientId: ClientId,
        draftId: String,
    ): EmailSentResult
}

// ==================== DATA MODELS ====================

@Serializable
data class Email(
    val id: String,
    val threadId: String,
    val from: String,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    val htmlBody: String?,
    val date: String,
    val attachments: List<AttachmentInfo> = emptyList(),
)

@Serializable
data class EmailThread(
    val threadId: String,
    val subject: String,
    val emails: List<Email>,
    val participantCount: Int,
)

@Serializable
data class Attachment(
    val id: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Attachment) return false

        if (id != other.id) return false
        if (filename != other.filename) return false
        if (mimeType != other.mimeType) return false
        if (size != other.size) return false
        if (!content.contentEquals(other.content)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}

@Serializable
data class AttachmentInfo(
    val id: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
)

@Serializable
data class EmailFilters(
    val from: String? = null,
    val subject: String? = null,
    val hasAttachment: Boolean? = null,
    val unread: Boolean? = null,
)

@Serializable
data class DraftEmailRequest(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    val htmlBody: String? = null,
)

@Serializable
data class EmailDraft(
    val draftId: String,
    val to: List<String>,
    val subject: String,
    val body: String,
    val created: String,
)

@Serializable
data class EmailSentResult(
    val emailId: String,
    val sentAt: String,
    val success: Boolean,
)
