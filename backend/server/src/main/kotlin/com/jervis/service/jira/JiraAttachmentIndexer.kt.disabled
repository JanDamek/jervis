package com.jervis.service.jira

import com.jervis.common.client.ITikaClient
import com.jervis.common.dto.TikaProcessRequest
import com.jervis.domain.atlassian.AtlassianConnection
import com.jervis.rag.DocumentToStore
import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeType
import com.jervis.repository.AtlassianConnectionMongoRepository
import com.jervis.repository.JiraIssueIndexMongoRepository
import com.jervis.service.atlassian.AtlassianConnectionService
import com.jervis.service.error.ErrorLogService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
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
    private val knowledgeService: KnowledgeService,
    private val tikaClient: ITikaClient,
    private val webClientBuilder: WebClient.Builder,
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val connectionService: AtlassianConnectionService,
    private val errorLogService: ErrorLogService,
    private val issueIndexRepository: JiraIssueIndexMongoRepository,
    private val rateLimiter: com.jervis.service.ratelimit.DomainRateLimiterService,
) {
    /**
     * Index all attachments for a given Jira issue.
     * Fetches attachment metadata, downloads content, extracts text, and indexes.
     * Only indexes NEW attachments that haven't been indexed before.
     */
    suspend fun indexIssueAttachments(
        accountId: ObjectId,
        conn: AtlassianConnection,
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

            // Load existing index document to check which attachments are already indexed
            val indexDoc = issueIndexRepository.findByAccountIdAndIssueKey(accountId, issueKey)
            val alreadyIndexed = indexDoc?.indexedAttachmentIds?.toSet() ?: emptySet()

            val newlyIndexedIds = mutableListOf<String>()

            attachments.forEachIndexed { index, attachment ->
                // Skip if already indexed
                if (alreadyIndexed.contains(attachment.id)) {
                    logger.debug { "JIRA_ATTACHMENT: Skipping already indexed attachment ${attachment.id}" }
                    return@forEachIndexed
                }

                try {
                    indexAttachment(
                        conn = conn,
                        issueKey = issueKey,
                        attachment = attachment,
                        clientId = clientId,
                        tenantHost = tenantHost,
                        projectId = projectId,
                    )
                    newlyIndexedIds.add(attachment.id)
                } catch (e: Exception) {
                    // If this looks like an auth error, mark connection INVALID and stop further attachment processing
                    if (isAuthError(e)) {
                        try {
                            val doc = connectionRepository.findById(accountId)
                            if (doc != null) {
                                connectionService.markAuthInvalid(doc, clientId, e.message)
                            }
                        } catch (inner: Exception) {
                            logger.warn(inner) { "Failed to mark Jira connection INVALID after auth error while indexing attachments" }
                        }
                        // Persist the exception to Error Logs for visibility in UI
                        runCatching { errorLogService.recordError(e, clientId = clientId, projectId = projectId) }
                        // stop processing further attachments for this issue/client
                        return@withContext
                    }

                    logger.warn(e) { "JIRA_ATTACHMENT: Failed to index attachment ${attachment.filename} for issue $issueKey" }
                    // Persist non-auth errors as well
                    runCatching { errorLogService.recordError(e, clientId = clientId, projectId = projectId) }
                }
            }

            // Update index document with newly indexed attachment IDs
            if (newlyIndexedIds.isNotEmpty()) {
                val updatedDoc =
                    if (indexDoc == null) {
                        com.jervis.entity.jira.JiraIssueIndexDocument(
                            accountId = accountId,
                            clientId = clientId,
                            issueKey = issueKey,
                            projectKey = "", // Will be filled by orchestrator
                            indexedAttachmentIds = newlyIndexedIds,
                            updatedAt = Instant.now(),
                        )
                    } else {
                        indexDoc.copy(
                            indexedAttachmentIds = (indexDoc.indexedAttachmentIds + newlyIndexedIds).distinct(),
                            updatedAt = Instant.now(),
                        )
                    }
                issueIndexRepository.save(updatedDoc)
                logger.info {
                    "JIRA_ATTACHMENT: Updated index document with ${newlyIndexedIds.size} new attachment IDs for issue $issueKey"
                }
            }
        }.onFailure { e ->
            // Top-level failure when fetching attachments metadata or other fatal error
            // If auth error, mark connection invalid so UI can prompt user to fix it
            if (isAuthError(e)) {
                try {
                    val doc = connectionRepository.findById(accountId)
                    if (doc != null) {
                        connectionService.markAuthInvalid(doc, clientId, e.message)
                    }
                } catch (inner: Exception) {
                    logger.warn(inner) { "Failed to mark Jira connection INVALID after auth error while fetching attachments" }
                }
                // Persist auth error for visibility
                runCatching { errorLogService.recordError(e, clientId = clientId, projectId = projectId) }
                return@withContext
            }

            logger.error(e) { "JIRA_ATTACHMENT: Failed to fetch attachments for issue $issueKey" }
            // Persist other errors too
            runCatching { errorLogService.recordError(e, clientId = clientId, projectId = projectId) }
        }
    }

    private suspend fun indexAttachment(
        conn: AtlassianConnection,
        issueKey: String,
        attachment: AttachmentMetadata,
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
        val processingResult =
            tikaClient.process(
                TikaProcessRequest(
                    source =
                        TikaProcessRequest.Source.FileBytes(
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

        val documentToStore =
            DocumentToStore(
                documentId = "jira:$issueKey:attachment:${attachment.id}",
                content = processingResult.plainText,
                clientId = clientId,
                projectId = projectId,
                type = KnowledgeType.DOCUMENT,
                embeddingType = EmbeddingType.TEXT,
                title = attachment.filename,
                location = "https://$tenantHost/secure/attachment/${attachment.id}/${attachment.filename}",
            )

        knowledgeService
            .store(com.jervis.rag.StoreRequest(listOf(documentToStore)))

        logger.info { "JIRA_ATTACHMENT: Indexed ${attachment.filename}" }
    }

    private suspend fun fetchAttachmentMetadata(
        conn: AtlassianConnection,
        issueKey: String,
    ): List<AttachmentMetadata> {
        val url = "https://${conn.tenant.value}/rest/api/3/issue/$issueKey?fields=attachment"
        rateLimiter.acquirePermit(url)

        val client = webClientBuilder.baseUrl("https://${conn.tenant.value}").build()

        val response: IssueDto =
            client
                .get()
                .uri { b ->
                    b
                        .path("/rest/api/3/issue/{key}")
                        .queryParam("fields", "attachment")
                        .build(issueKey)
                }.header("Authorization", basicAuth(conn))
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
        conn: AtlassianConnection,
        contentUrl: String,
    ): ByteArray {
        // Apply rate limiting before download
        rateLimiter.acquirePermit(contentUrl)

        // Configure WebClient with larger buffer for attachments (up to 64MB)
        // This prevents DataBufferLimitException when downloading large files
        val client =
            webClientBuilder
                .exchangeStrategies(
                    org.springframework.web.reactive.function.client.ExchangeStrategies
                        .builder()
                        .codecs { it.defaultCodecs().maxInMemorySize(MAX_ATTACHMENT_SIZE_BYTES) }
                        .build(),
                ).build()

        return client
            .get()
            .uri(contentUrl)
            .header("Authorization", basicAuth(conn))
            .retrieve()
            .awaitBody()
    }

    private fun basicAuth(conn: AtlassianConnection): String {
        val tokenPair = (conn.email ?: "") + ":" + conn.accessToken
        val encoded = Base64.getEncoder().encodeToString(tokenPair.toByteArray())
        return "Basic $encoded"
    }

    private fun parseJiraDate(value: String): Instant =
        runCatching { Instant.parse(value) }
            .getOrElse {
                runCatching {
                    val f =
                        java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                    java.time.OffsetDateTime
                        .parse(value, f)
                        .toInstant()
                }.getOrElse {
                    val f2 =
                        java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
                    java.time.OffsetDateTime
                        .parse(value, f2)
                        .toInstant()
                }
            }

    private fun isAuthError(t: Throwable?): Boolean {
        if (t == null) return false
        if (t is WebClientResponseException) {
            val code = t.statusCode.value()
            return code == 401 || code == 403
        }
        val cause = t.cause
        if (cause is WebClientResponseException) {
            val code = cause.statusCode.value()
            return code == 401 || code == 403
        }
        val msg = t.message ?: return false
        return msg.contains("401") || msg.contains("403") || msg.contains("Unauthorized", true) || msg.contains("Forbidden", true)
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

    companion object {
        // Maximum attachment size to download and process (64 MB)
        // Prevents DataBufferLimitException for large Jira attachments
        private const val MAX_ATTACHMENT_SIZE_BYTES = 64 * 1024 * 1024
    }
}
