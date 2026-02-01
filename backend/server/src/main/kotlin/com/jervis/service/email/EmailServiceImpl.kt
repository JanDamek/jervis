package com.jervis.service.email

import com.jervis.repository.EmailMessageIndexRepository
import com.jervis.types.ClientId
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service

@Service
class EmailServiceImpl(
    private val repository: EmailMessageIndexRepository,
) : EmailService {
    override suspend fun searchEmails(
        clientId: ClientId,
        query: String,
        filters: EmailFilters,
        maxResults: Int,
    ): List<Email> {
        // Simple implementation: fetch all for client and filter in memory
        // In a real app, this would be a MongoDB query
        return repository
            .findAll()
            .toList()
            .filter { it.clientId == clientId }
            .filter { doc ->
                val matchesQuery =
                    query.isBlank() || doc.subject?.contains(
                        query,
                        ignoreCase = true,
                    ) == true || doc.textBody?.contains(
                        query,
                        ignoreCase = true,
                    ) == true
                val matchesFrom = filters.from == null || doc.from?.contains(filters.from, ignoreCase = true) == true
                val matchesSubject =
                    filters.subject == null || doc.subject?.contains(filters.subject, ignoreCase = true) == true
                matchesQuery && matchesFrom && matchesSubject
            }.take(maxResults)
            .map { it.toEmail() }
    }

    override suspend fun getEmail(
        clientId: ClientId,
        emailId: String,
    ): Email {
        val doc =
            repository.findAll().toList().find { it.clientId == clientId && (it.messageId == emailId || it.messageUid == emailId) }
                ?: throw NoSuchElementException("Email not found: $emailId")
        return doc.toEmail()
    }

    override suspend fun getThread(
        clientId: ClientId,
        threadId: String,
    ): EmailThread {
        // Implementation depends on how threads are stored. Currently not fully supported in schema.
        val emails =
            repository
                .findAll()
                .toList()
                .filter { it.clientId == clientId && (it.messageId?.contains(threadId) == true || it.messageUid.contains(threadId)) } // Mock thread logic
                .map { it.toEmail() }

        return EmailThread(
            threadId = threadId,
            subject = emails.firstOrNull()?.subject ?: "",
            emails = emails,
            participantCount = emails.flatMap { it.to + it.cc + it.from }.distinct().size,
        )
    }

    override suspend fun getAttachment(
        clientId: ClientId,
        attachmentId: String,
    ): Attachment = throw UnsupportedOperationException("Attachment retrieval from repository not implemented yet")

    override suspend fun draftEmail(
        clientId: ClientId,
        request: DraftEmailRequest,
    ): EmailDraft = throw UnsupportedOperationException("Write operations are not allowed yet (Read-only mode)")

    override suspend fun sendEmail(
        clientId: ClientId,
        draftId: String,
    ): EmailSentResult = throw UnsupportedOperationException("Write operations are not allowed yet (Read-only mode)")

    private fun com.jervis.entity.email.EmailMessageIndexDocument.toEmail() =
        Email(
            id = messageId ?: messageUid,
            threadId = messageId ?: messageUid, // Mock
            from = from ?: "unknown",
            to = to,
            cc = emptyList(),
            bcc = emptyList(),
            subject = subject ?: "(No Subject)",
            body = textBody ?: "",
            htmlBody = htmlBody,
            date = sentDate.toString(),
            attachments =
                attachments.map {
                    AttachmentInfo(
                        it.contentId ?: it.filename,
                        it.filename,
                        it.contentType,
                        it.size,
                    )
                },
        )
}
