package com.jervis.service.jira

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.domain.jira.JiraConnection
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.service.rag.RagIndexingService
import com.jervis.service.text.TextChunkingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Instant
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * Service for indexing Jira issue attachments.
 *
 * Responsibilities:
 * - Fetch attachments from Jira API
 * - Download attachment content
 * - Extract plain text via Tika
 * - Chunk and index into RAG
 *
 * Note: Only processes text-extractable attachments (PDF, DOCX, images with OCR, etc.)
 * Binary files without text content are skipped.
 */
@Service
class JiraAttachmentIndexer(
    private val ragIndexingService: RagIndexingService,
    private val tikaClient: ITikaClient,
    private val textChunkingService: TextChunkingService,
    private val webClientBuilder: WebClient.Builder,
) {
    /**
     * Index all attachments for a given Jira issue.
     * Fetches attachment metadata, downloads content, extracts text, and indexes.
     */
    suspend fun indexIssueAttachments(
        conn: JiraConnection,
        issueKey: String,
        clientId: ObjectId,
        tenantHost: String,
        projectId: ObjectId? = null,
    ) = withContext(Dispatchers.IO) {
        logger.debug { "JIRA_ATTACHMENT: Fetching attachments for issue $issueKey" }

        runCatching {
            val attachments = fetchAttachmentMetadata(conn, issueKey)

            if (attachments.isEmpty()) {
                logger.debug { "JIRA_ATTACHMENT: No attachments found for issue $issueKey" }
                return@withContext
            }

            logger.info { "JIRA_ATTACHMENT: Found ${attachments.size} attachments for issue $issueKey" }

            attachments.forEachIndexed { index, attachment ->
                try {
                    indexAttachment(
                        conn = conn,
                        issueKey = issueKey,
                        attachment = attachment,
                        attachmentIndex = index,
                        clientId = clientId,
                        tenantHost = tenantHost,
                        projectId = projectId,
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "JIRA_ATTACHMENT: Failed to index attachment ${attachment.filename} for issue $issueKey" }
                }
            }
        }.onFailure { e ->
            logger.error(e) { "JIRA_ATTACHMENT: Failed to fetch attachments for issue $issueKey" }
        }
    }

    private suspend fun indexAttachment(
        conn: JiraConnection,
        issueKey: String,
        attachment: AttachmentMetadata,
        attachmentIndex: Int,
        clientId: ObjectId,
        tenantHost: String,
        projectId: ObjectId?,
    ) {
        logger.debug { "JIRA_ATTACHMENT: Processing ${attachment.filename} (${attachment.mimeType})" }

        // Download attachment content
        val content = downloadAttachment(conn, attachment.contentUrl)

        if (content.isEmpty()) {
            logger.debug { "JIRA_ATTACHMENT: Skipping empty attachment ${attachment.filename}" }
            return
        }

        // Extract plain text via Tika
        val processingResult = tikaClient.process(
            TikaProcessRequest(
                source = TikaProcessRequest.Source.FileBytes(
                    fileName = attachment.filename,
                    dataBase64 = Base64.getEncoder().encodeToString(content),
                ),
                includeMetadata = true,
            ),
        )

        if (!processingResult.success || processingResult.plainText.isBlank()) {
            logger.debug { "JIRA_ATTACHMENT: No text extracted from ${attachment.filename}" }
            return
        }

        // Chunk and index
        val chunks = textChunkingService.splitText(processingResult.plainText)
        logger.debug { "JIRA_ATTACHMENT: Split ${attachment.filename} into ${chunks.size} chunks" }

        chunks.forEachIndexed { chunkIndex, chunk ->
            ragIndexingService.indexDocument(
                RagDocument(
                    projectId = projectId, // Map to Jervis project if exists
                    clientId = clientId,
                    text = chunk.text(),
                    ragSourceType = RagSourceType.JIRA_ATTACHMENT,
                    createdAt = attachment.created,
                    sourceUri = "https://$tenantHost/secure/attachment/${attachment.id}/${attachment.filename}",
                    // Universal metadata fields
                    from = attachment.author,
                    subject = "Jira attachment: ${attachment.filename}",
                    timestamp = attachment.created.toString(),
                    parentRef = issueKey,
                    chunkId = chunkIndex,
                    chunkOf = chunks.size,
                    fileName = attachment.filename,
                ),
                ModelTypeEnum.EMBEDDING_TEXT,
            )
        }

        logger.info { "JIRA_ATTACHMENT: Indexed ${attachment.filename} with ${chunks.size} chunks" }
    }

    private suspend fun fetchAttachmentMetadata(
        conn: JiraConnection,
        issueKey: String,
    ): List<AttachmentMetadata> {
        val client = webClientBuilder.baseUrl("https://${conn.tenant.value}").build()

        val response: IssueDto = client
            .get()
            .uri { b ->
                b.path("/rest/api/3/issue/{key}")
                    .queryParam("fields", "attachment")
                    .build(issueKey)
            }
            .header("Authorization", basicAuth(conn))
            .retrieve()
            .awaitBody()

        return response.fields?.attachment?.mapNotNull { dto ->
            val id = dto.id ?: return@mapNotNull null
            val filename = dto.filename ?: return@mapNotNull null
            val contentUrl = dto.content ?: return@mapNotNull null
            val mimeType = dto.mimeType ?: "application/octet-stream"
            val size = dto.size ?: 0
            val created = dto.created?.let { parseJiraDate(it) } ?: Instant.now()
            val author = dto.author?.displayName ?: "unknown"

            AttachmentMetadata(
                id = id,
                filename = filename,
                contentUrl = contentUrl,
                mimeType = mimeType,
                size = size,
                created = created,
                author = author,
            )
        } ?: emptyList()
    }

    private suspend fun downloadAttachment(
        conn: JiraConnection,
        contentUrl: String,
    ): ByteArray {
        val client = webClientBuilder.build()

        return client
            .get()
            .uri(contentUrl)
            .header("Authorization", basicAuth(conn))
            .retrieve()
            .awaitBody()
    }

    private fun basicAuth(conn: JiraConnection): String {
        val tokenPair = (conn.email ?: "") + ":" + conn.accessToken
        val encoded = Base64.getEncoder().encodeToString(tokenPair.toByteArray())
        return "Basic $encoded"
    }

    private fun parseJiraDate(value: String): Instant =
        runCatching { Instant.parse(value) }
            .getOrElse {
                runCatching {
                    val f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                    java.time.OffsetDateTime.parse(value, f).toInstant()
                }.getOrElse {
                    val f2 = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
                    java.time.OffsetDateTime.parse(value, f2).toInstant()
                }
            }

    // DTOs
    private data class IssueDto(
        val key: String? = null,
        val fields: FieldsDto? = null,
    )

    private data class FieldsDto(
        val attachment: List<AttachmentDto>? = null,
    )

    private data class AttachmentDto(
        val id: String? = null,
        val filename: String? = null,
        val author: AuthorDto? = null,
        val created: String? = null,
        val size: Long? = null,
        val mimeType: String? = null,
        val content: String? = null, // URL to download content
    )

    private data class AuthorDto(
        val displayName: String? = null,
    )

    private data class AttachmentMetadata(
        val id: String,
        val filename: String,
        val contentUrl: String,
        val mimeType: String,
        val size: Long,
        val created: Instant,
        val author: String,
    )
}
