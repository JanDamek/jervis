package com.jervis.integration.wiki.internal.indexing

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.atlassian.ConfluenceAttachmentDownloadRequest
import com.jervis.common.dto.atlassian.ConfluencePageRequest
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.domain.atlassian.AttachmentType
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.integration.wiki.internal.entity.WikiPageIndexDocument
import com.jervis.integration.wiki.internal.state.WikiStateManager
import com.jervis.service.background.TaskService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.storage.DirectoryStructureService
import com.jervis.types.SourceUrn
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for Confluence pages.
 *
 * NEW ARCHITECTURE (Two-Phase Indexing):
 * - CentralPoller fetches MINIMAL data for change detection → stores in MongoDB as NEW
 * - This indexer:
 *   1. Reads NEW documents from MongoDB
 *   2. Fetches COMPLETE page details from Confluence API (getConfluencePage)
 *   3. Creates CONFLUENCE_PROCESSING PendingTask with full content
 * - KoogQualifierAgent (CPU) handles ALL structuring:
 *   - Decides on Graph nodes (page metadata, space, creator, relations)
 *   - Decides on chunking strategy (semantic, context-aware)
 *   - Creates RAG chunks with semantic meaning
 *   - Links Graph ↔ RAG bi-directionally
 *   - Routes to GPU (READY_FOR_GPU) ONLY if complex analysis needed
 *
 * ETL Flow: MongoDB (NEW minimal) → API (full details) → CONFLUENCE_PROCESSING Task → KoogQualifierAgent → Graph + RAG
 */
@Service
@Profile("!cli")
@Order(10) // Start after WeaviateSchemaInitializer
class WikiContinuousIndexer(
    private val stateManager: WikiStateManager,
    private val taskService: TaskService,
    private val connectionService: ConnectionService,
    private val atlassianClient: IAtlassianClient,
    private val directoryStructureService: DirectoryStructureService,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting WikiContinuousIndexer (MongoDB → Weaviate)..." }
        scope.launch {
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "Continuous indexer crashed" } }
        }
    }

    private suspend fun indexContinuously() {
        // Continuous flow of NEW pages from MongoDB
        stateManager.continuousNewPagesAllAccounts().collect { doc ->
            try {
                indexPage(doc)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index Confluence page ${doc.pageId}" }
                stateManager.markAsFailed(doc, "Indexing error: ${e.message}")
            }
        }
    }

    private suspend fun indexPage(doc: WikiPageIndexDocument) {
        logger.debug { "Processing Confluence page ${doc.pageId} (${doc.title})" }

        try {
            // Fetch complete page details from Confluence API
            val connection = connectionService.findById(doc.connectionDocumentId)
            require(connection?.connectionType == ConnectionDocument.ConnectionTypeEnum.HTTP) {
                "Connection ${doc.connectionDocumentId} is not an HTTP connection"
            }

            val credentials = connection.credentials
            require(credentials is ConnectionDocument.HttpCredentials) {
                "Connection ${doc.connectionDocumentId} does not have HTTP credentials"
            }

            val pageRequest =
                ConfluencePageRequest(
                    baseUrl = connection.baseUrl,
                    authType =
                        when (credentials) {
                            is ConnectionDocument.HttpCredentials.Basic -> "BASIC"
                            is ConnectionDocument.HttpCredentials.Bearer -> "BEARER"
                        },
                    basicUsername =
                        when (credentials) {
                            is ConnectionDocument.HttpCredentials.Basic -> credentials.username
                            else -> null
                        },
                    basicPassword =
                        when (credentials) {
                            is ConnectionDocument.HttpCredentials.Basic -> credentials.password
                            else -> null
                        },
                    bearerToken =
                        when (credentials) {
                            is ConnectionDocument.HttpCredentials.Bearer -> credentials.token
                            else -> null
                        },
                    pageId = doc.pageId,
                )

            val pageDetails = atlassianClient.getConfluencePage(pageRequest)

            // Extract raw Confluence storage content (XML/ADF format with ac:* tags)
            // Will be automatically cleaned by TaskService via Tika before storage
            val rawContent = pageDetails.body?.storage?.value ?: ""

            // Build Confluence page content for a task (raw content will be cleaned in TaskService)
            val pageContent =
                buildString {
                    append("# ${pageDetails.title}\n\n")
                    append("**Space:** ${pageDetails.spaceKey ?: "N/A"}\n")
                    append("**Space Name:** ${pageDetails.spaceName ?: "N/A"}\n")
                    append("**Type:** ${pageDetails.type ?: "page"}\n")
                    append("**Status:** ${pageDetails.status ?: "current"}\n")
                    if (!pageDetails.version
                            ?.by
                            ?.displayName
                            .isNullOrBlank()
                    ) {
                        append("**Last Modified By:** ${pageDetails.version?.by?.displayName}\n")
                    }
                    if (!pageDetails.createdDate.isNullOrBlank()) {
                        append("**Created:** ${pageDetails.createdDate}\n")
                    }
                    if (!pageDetails.lastModified.isNullOrBlank()) {
                        append("**Updated:** ${pageDetails.lastModified}\n")
                    }
                    if (!pageDetails.parentId.isNullOrBlank()) {
                        append("**Parent Page ID:** ${pageDetails.parentId}\n")
                    }
                    append("\n")

                    if (rawContent.isNotBlank()) {
                        append("## Content\n\n")
                        append(rawContent)
                        append("\n\n")
                    }

                    if (pageDetails.labels?.isNotEmpty() == true) {
                        append("**Labels:** ${pageDetails.labels!!.joinToString(", ")}\n\n")
                    }

                    // Add metadata for qualifier
                    append("## Document Metadata\n")
                    append("- **Source:** Confluence Page\n")
                    append("- **Document ID:** confluence:${doc.pageId}\n")
                    append("- **Connection ID:** ${doc.connectionDocumentId}\n")
                    append("- **Page ID:** ${doc.pageId}\n")
                    append("- **Space Key:** ${pageDetails.spaceKey ?: "N/A"}\n")
                    if (pageDetails.parentId != null) {
                        append("- **Parent Page ID:** ${pageDetails.parentId}\n")
                    }
                }

            // Download and store attachments (images, documents, etc.)
            logger.debug { "Page ${doc.pageId} has ${pageDetails.attachments?.size ?: 0} attachments" }
            val attachmentMetadata =
                pageDetails.attachments?.mapNotNull { attachment ->
                    try {
                        // Skip attachments without download URL
                        val downloadUrl = attachment.downloadUrl
                        if (downloadUrl == null) {
                            logger.warn { "Confluence attachment ${attachment.id} has no download URL, skipping" }
                            return@mapNotNull null
                        }

                        // Build full download URL (downloadUrl from API is relative, needs baseUrl + /wiki prefix)
                        val fullDownloadUrl =
                            if (downloadUrl.startsWith("http")) {
                                downloadUrl
                            } else {
                                "${connection.baseUrl.trimEnd('/')}/wiki$downloadUrl"
                            }

                        val downloadRequest =
                            ConfluenceAttachmentDownloadRequest(
                                baseUrl = connection.baseUrl,
                                authType =
                                    when (credentials) {
                                        is ConnectionDocument.HttpCredentials.Basic -> "BASIC"
                                        is ConnectionDocument.HttpCredentials.Bearer -> "BEARER"
                                    },
                                basicUsername =
                                    when (credentials) {
                                        is ConnectionDocument.HttpCredentials.Basic -> credentials.username
                                        else -> null
                                    },
                                basicPassword =
                                    when (credentials) {
                                        is ConnectionDocument.HttpCredentials.Basic -> credentials.password
                                        else -> null
                                    },
                                bearerToken =
                                    when (credentials) {
                                        is ConnectionDocument.HttpCredentials.Bearer -> credentials.token
                                        else -> null
                                    },
                                attachmentDownloadUrl = fullDownloadUrl,
                            )

                        val binaryData = atlassianClient.downloadConfluenceAttachment(downloadRequest)

                        if (binaryData == null || binaryData.isEmpty()) {
                            logger.warn { "Failed to download Confluence attachment ${attachment.id}, skipping" }
                            return@mapNotNull null
                        }

                        // Store attachment in filesystem
                        val storagePath =
                            directoryStructureService.storeAttachment(
                                clientId = doc.clientId,
                                filename = attachment.title,
                                binaryData = binaryData,
                            )

                        // Determine an attachment type from the MIME type
                        val attachmentType =
                            when {
                                attachment.mediaType?.startsWith("image/") == true -> AttachmentType.IMAGE
                                attachment.mediaType?.startsWith("application/pdf") == true -> AttachmentType.DOCUMENT
                                attachment.mediaType?.startsWith("text/") == true -> AttachmentType.DOCUMENT
                                else -> AttachmentType.UNKNOWN
                            }

                        AttachmentMetadata(
                            id = attachment.id,
                            filename = attachment.title,
                            mimeType = attachment.mediaType ?: "application/octet-stream",
                            sizeBytes = attachment.fileSize ?: binaryData.size.toLong(),
                            storagePath = storagePath,
                            type = attachmentType,
                            visionAnalysis = null,
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to download/store Confluence attachment ${attachment.id}: ${e.message}" }
                        null
                    }
                } ?: emptyList()

            // Create WIKI_PROCESSING task with attachments
            taskService.createTask(
                taskType = TaskTypeEnum.WIKI_PROCESSING,
                content = pageContent,
                projectId = doc.projectId,
                clientId = doc.clientId,
                correlationId = "confluence:${doc.pageId}",
                sourceUrn =
                    SourceUrn.confluence(
                        connectionId = doc.connectionDocumentId.value,
                        pageId = doc.pageId,
                    ),
                attachments = attachmentMetadata,
            )

            // Convert to INDEXED state - delete full content, keep minimal tracking
            stateManager.markAsIndexed(doc)

            logger.info { "Created CONFLUENCE_PROCESSING task for Confluence page: ${doc.pageId} (${doc.title})" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task for Confluence page ${doc.pageId}" }
            stateManager.markAsFailed(doc, "Task creation failed: ${e.message}")
        }
    }
}
