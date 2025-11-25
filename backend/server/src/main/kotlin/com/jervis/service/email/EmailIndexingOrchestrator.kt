package com.jervis.service.email

import com.jervis.entity.email.EmailMessageIndexDocument
import com.jervis.rag.DocumentToStore
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeType
import com.jervis.rag.StoreRequest
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Orchestrator for indexing email messages into RAG.
 * Reads complete data from EmailMessageIndexDocument (MongoDB), NO EMAIL SERVER CALLS.
 */
@Service
class EmailIndexingOrchestrator(
    private val knowledgeService: KnowledgeService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Index a single email message from MongoDB document.
     * All data is already in the document (fetched by CentralPoller).
     * Returns result with chunk count.
     */
    suspend fun indexSingleEmail(
        clientId: ObjectId,
        document: EmailMessageIndexDocument,
    ): IndexingResult {
        logger.debug { "Indexing email: ${document.subject}" }

        // Build email content for RAG
        val emailContent = buildString {
            append("# Email: ${document.subject}\n\n")
            append("**From:** ${document.from}\n")
            if (document.to.isNotEmpty()) {
                append("**To:** ${document.to.joinToString(", ")}\n")
            }
            if (document.cc.isNotEmpty()) {
                append("**Cc:** ${document.cc.joinToString(", ")}\n")
            }
            append("**Date:** ${document.sentDate ?: document.receivedDate}\n")
            append("**Folder:** ${document.folder}\n")
            append("\n---\n\n")

            // Prefer text body, fallback to HTML
            val body = document.textBody ?: document.htmlBody
            if (!body.isNullOrBlank()) {
                append(body)
            }

            if (document.attachments.isNotEmpty()) {
                append("\n\n## Attachments\n")
                document.attachments.forEach { att ->
                    append("- ${att.filename} (${att.contentType}, ${att.size} bytes)\n")
                }
            }
        }

        // Store in RAG
        val documentsToStore = listOf(
            DocumentToStore(
                documentId = "email:${document.id.toHexString()}",
                content = emailContent,
                clientId = clientId,
                type = KnowledgeType.DOCUMENT,
                title = document.subject,
                location = "Email (${document.folder})",
                projectId = null,
            )
        )

        return try {
            val result = knowledgeService.store(StoreRequest(documents = documentsToStore))
            val chunkCount = result.documents.firstOrNull()?.totalChunks ?: 0

            logger.info { "Indexed email ${document.subject}: $chunkCount chunks" }

            IndexingResult(
                success = true,
                chunkCount = chunkCount,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to store email ${document.subject} in RAG" }
            IndexingResult(
                success = false,
                chunkCount = 0,
            )
        }
    }

    data class IndexingResult(
        val success: Boolean,
        val chunkCount: Int = 0,
    )
}
