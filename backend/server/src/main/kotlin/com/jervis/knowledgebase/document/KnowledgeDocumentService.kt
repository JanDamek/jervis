package com.jervis.knowledgebase.document

import com.jervis.entity.knowledge.KnowledgeAttachment
import com.jervis.entity.knowledge.KnowledgeDocument
import com.jervis.repository.KnowledgeDocumentRepository
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn
import mu.KotlinLogging
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant

@Service
class KnowledgeDocumentService(
    private val repository: KnowledgeDocumentRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun upsert(
        request: KnowledgeDocumentRequest,
    ): KnowledgeDocument {
        val contentHash = sha256(request.content)
        val existing =
            repository.findFirstByClientIdAndSourceUrnAndContentHash(
                clientId = request.clientId,
                sourceUrn = request.sourceUrn,
                contentHash = contentHash,
            )

        if (existing != null) {
            val updated =
                existing.copy(
                    title = request.title ?: existing.title,
                    rawContent = request.rawContent ?: existing.rawContent,
                    attachments = if (request.attachments.isNotEmpty()) request.attachments else existing.attachments,
                    metadata = mergeMetadata(existing.metadata, request.metadata),
                    lastSeenAt = Instant.now(),
                    seenCount = existing.seenCount + 1,
                    observedAt = request.observedAt,
                )
            return repository.save(updated)
        }

        val doc =
            KnowledgeDocument(
                clientId = request.clientId,
                projectId = request.projectId,
                sourceUrn = request.sourceUrn,
                sourceType = request.sourceType,
                title = request.title,
                content = request.content,
                rawContent = request.rawContent,
                contentHash = contentHash,
                attachments = request.attachments,
                metadata = request.metadata,
                observedAt = request.observedAt,
            )

        return runCatching { repository.save(doc) }
            .recoverCatching { e ->
                if (e is DuplicateKeyException) {
                    repository.findFirstByClientIdAndSourceUrnAndContentHash(
                        clientId = request.clientId,
                        sourceUrn = request.sourceUrn,
                        contentHash = contentHash,
                    ) ?: throw e
                } else {
                    throw e
                }
            }
            .getOrElse { e ->
                logger.error(e) { "Failed to store knowledge document for ${request.sourceUrn.value}" }
                throw e
            }
    }

    private fun mergeMetadata(
        existing: Map<String, String>,
        incoming: Map<String, String>,
    ): Map<String, String> =
        if (incoming.isEmpty()) {
            existing
        } else {
            existing + incoming
        }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

data class KnowledgeDocumentRequest(
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val sourceUrn: SourceUrn,
    val sourceType: String,
    val title: String? = null,
    val content: String,
    val rawContent: String? = null,
    val attachments: List<KnowledgeAttachment> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val observedAt: Instant = Instant.now(),
)
