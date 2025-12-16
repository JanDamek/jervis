package com.jervis.service.confluence

import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.entity.confluence.ConfluencePageIndexDocument
import com.jervis.service.background.PendingTaskService
import com.jervis.service.confluence.state.ConfluenceStateManager
import com.jervis.service.indexing.status.IndexingStatusRegistry
import com.jervis.service.text.TikaTextExtractionService
import com.jervis.types.SourceUrn
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for Confluence pages.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - CentralPoller fetches FULL data from API → stores in MongoDB as NEW
 * - This indexer reads NEW documents from MongoDB (NO API CALLS)
 * - Creates CONFLUENCE_PROCESSING PendingTask with complete content
 * - KoogQualifierAgent (CPU) handles ALL structuring:
 *   - Decides on Graph nodes (page metadata, space, creator, relations)
 *   - Decides on chunking strategy (semantic, context-aware)
 *   - Creates RAG chunks with semantic meaning
 *   - Links Graph ↔ RAG bi-directionally
 *   - Routes to GPU (READY_FOR_GPU) ONLY if complex analysis needed
 *
 * Pure ETL: MongoDB (NEW) → CONFLUENCE_PROCESSING Task → KoogQualifierAgent → Graph + RAG
 */
@Service
@Order(10) // Start after WeaviateSchemaInitializer
class ConfluenceContinuousIndexer(
    private val stateManager: ConfluenceStateManager,
    private val pendingTaskService: PendingTaskService,
    private val indexingRegistry: IndexingStatusRegistry,
    private val tikaTextExtractionService: TikaTextExtractionService,
    private val directoryStructureService: com.jervis.service.storage.DirectoryStructureService,
    private val connectionService: com.jervis.service.connection.ConnectionService,
    private val atlassianClient: com.jervis.common.client.IAtlassianClient,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting ConfluenceContinuousIndexer (MongoDB → Weaviate)..." }
        scope.launch {
            kotlin.runCatching {
                indexingRegistry.start(
                    IndexingStatusRegistry.ToolStateEnum.CONFLUENCE,
                    displayName = "Atlassian (Confluence)",
                    message = "Starting continuous Confluence indexing from MongoDB",
                )
            }
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "Continuous indexer crashed" } }
                .also {
                    kotlin.runCatching {
                        indexingRegistry.finish(
                            IndexingStatusRegistry.ToolStateEnum.CONFLUENCE,
                            message = "Confluence indexer stopped",
                        )
                    }
                }
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

    private suspend fun indexPage(doc: ConfluencePageIndexDocument) {
        // Only process NEW documents (type-safe check)
        if (doc !is ConfluencePageIndexDocument.New) {
            logger.warn { "Received non-NEW document for indexing: ${doc.pageId}" }
            return
        }

        logger.debug { "Processing Confluence page ${doc.pageId} (${doc.title})" }

        try {
            // Clean Confluence content through Tika (removes XML/HTML formatting like ac:link, ri:page)
            val cleanContent =
                if (!doc.content.isNullOrBlank()) {
                    tikaTextExtractionService.extractPlainText(
                        content = doc.content,
                        fileName = "confluence-${doc.pageId}.xml",
                    )
                } else {
                    ""
                }

            // Clean comments through Tika as well
            val cleanComments =
                doc.comments?.map { comment ->
                    comment.copy(
                        body =
                            tikaTextExtractionService.extractPlainText(
                                content = comment.body,
                                fileName = "confluence-comment.xml",
                            ),
                    )
                }

            // Build Confluence page content for task
            val pageContent =
                buildString {
                    append("# ${doc.title}\n\n")
                    append("**Space:** ${doc.spaceKey}\n")
                    append("**Type:** ${doc.pageType}\n")
                    append("**Status:** ${doc.status}\n")
                    if (!doc.creator.isNullOrBlank()) {
                        append("**Creator:** ${doc.creator}\n")
                    }
                    if (!doc.lastModifier.isNullOrBlank()) {
                        append("**Last Modified By:** ${doc.lastModifier}\n")
                    }
                    append("**Created:** ${doc.createdAt}\n")
                    append("**Updated:** ${doc.confluenceUpdatedAt}\n")
                    append("\n")

                    if (cleanContent.isNotBlank()) {
                        append("## Content\n\n")
                        append(cleanContent)
                        append("\n\n")
                    }

                    if (doc.labels?.isNotEmpty() == true) {
                        append("**Labels:** ${doc.labels.joinToString(", ")}\n\n")
                    }

                    // Add comments
                    if (cleanComments?.isNotEmpty() == true) {
                        append("## Comments\n\n")
                        cleanComments.forEachIndexed { index, comment ->
                            append("### Comment ${index + 1} by ${comment.author}\n")
                            append("**Created:** ${comment.created}\n\n")
                            append(comment.body)
                            append("\n\n")
                        }
                    }

                    // Add attachments list
                    if (doc.attachments?.isNotEmpty() == true) {
                        append("## Attachments\n")
                        doc.attachments.forEach { att ->
                            append("- ${att.filename} (${att.mimeType}, ${att.size} bytes)\n")
                        }
                        append("\n")
                    }

                    // Add metadata for qualifier
                    append("## Document Metadata\n")
                    append("- **Source:** Confluence Page\n")
                    append("- **Document ID:** confluence:${doc.pageId}\n")
                    append("- **Connection ID:** ${doc.connectionDocumentId}\n")
                    append("- **Page ID:** ${doc.pageId}\n")
                    append("- **Space Key:** ${doc.spaceKey}\n")
                    if (doc.parentPageId != null) {
                        append("- **Parent Page ID:** ${doc.parentPageId}\n")
                    }
                    append("- **Comment Count:** ${doc.comments?.size}\n")
                    append("- **Attachment Count:** ${doc.attachments?.size}\n")
                }

            // Process attachments for vision analysis
            val attachmentMetadata = processAttachments(doc)

            if (attachmentMetadata.isNotEmpty()) {
                logger.info { "Processed ${attachmentMetadata.size} attachments for vision analysis: ${doc.pageId}" }
            }

            // Create CONFLUENCE_PROCESSING task with attachments
            pendingTaskService.createTask(
                taskType = PendingTaskTypeEnum.CONFLUENCE_PROCESSING,
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

            // Report progress
            kotlin.runCatching {
                indexingRegistry.ensureTool(
                    IndexingStatusRegistry.ToolStateEnum.CONFLUENCE,
                    displayName = "Atlassian (Confluence)",
                )
                indexingRegistry.progress(
                    IndexingStatusRegistry.ToolStateEnum.CONFLUENCE,
                    processedInc = 1,
                    message = "Created task for ${doc.title}",
                )
            }

            logger.info { "Created CONFLUENCE_PROCESSING task for Confluence page: ${doc.pageId} (${doc.title})" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task for Confluence page ${doc.pageId}" }
            stateManager.markAsFailed(doc, "Task creation failed: ${e.message}")
        }
    }

    /**
     * Download and store Confluence attachments for vision analysis.
     * Only downloads image attachments (vision model input).
     */
    private suspend fun processAttachments(doc: ConfluencePageIndexDocument.New): List<com.jervis.entity.AttachmentMetadata> {
        val attachmentMetadataList = mutableListOf<com.jervis.entity.AttachmentMetadata>()

        // Filter attachments that should be processed with vision
        val visualAttachments =
            doc.attachments?.filter { att ->
                val type = com.jervis.entity.classifyAttachmentType(att.mimeType)
                type == com.jervis.entity.AttachmentType.IMAGE ||
                    type == com.jervis.entity.AttachmentType.PDF_SCANNED
            }

        if (visualAttachments?.isEmpty() == true) {
            return emptyList()
        }

        logger.debug { "Processing ${visualAttachments?.size} visual attachments for ${doc.pageId}" }

        // FAIL-FAST: If any attachment download/storage fails, exception propagates
        // This marks the entire Confluence page as FAILED for retry
        if (visualAttachments != null) {
            for (att in visualAttachments) {
                // Download attachment from Confluence via service-atlassian (rate-limited)
                val binaryData = downloadAttachment(att.downloadUrl, doc)

                // Get image dimensions for token estimation (if image)
                val (_, _) = extractImageDimensions(binaryData, att.mimeType)

                // Store in DirectoryStructureService
                val storagePath =
                    directoryStructureService.storeAttachment(
                        clientId = doc.clientId,
                        filename = att.filename,
                        binaryData = binaryData,
                    )

                // Create metadata
                val metadata =
                    com.jervis.entity.AttachmentMetadata(
                        id = att.id,
                        filename = att.filename,
                        mimeType = att.mimeType,
                        sizeBytes = att.size,
                        storagePath = storagePath,
                        type = com.jervis.entity.classifyAttachmentType(att.mimeType),
                        visionAnalysis = null, // Populated later by Qualifier
                    )

                attachmentMetadataList.add(metadata)
                logger.debug { "Stored attachment: ${att.filename} (${att.mimeType}, ${att.size} bytes) → $storagePath" }
            }
        }

        return attachmentMetadataList
    }

    /**
     * Download attachment binary data from Confluence URL via service-atlassian.
     * Uses rate-limited API client to respect Atlassian rate limits.
     */
    private suspend fun downloadAttachment(
        attachmentUrl: String,
        doc: ConfluencePageIndexDocument.New,
    ): ByteArray {
        // Load connection to get auth credentials
        val connection =
            connectionService.findById(doc.connectionDocumentId.value)
                ?: throw IllegalStateException("Connection not found: ${doc.connectionDocumentId}")

        if (connection !is com.jervis.entity.connection.ConnectionDocument.HttpConnectionDocument) {
            throw IllegalStateException("Connection is not HTTP: ${connection::class.simpleName}")
        }

        // Build request with auth credentials
        val request =
            com.jervis.common.dto.atlassian.JiraAttachmentDownloadRequest(
                baseUrl = connection.baseUrl,
                authType =
                    when (connection.credentials) {
                        is com.jervis.entity.connection.HttpCredentials.Basic -> "BASIC"
                        is com.jervis.entity.connection.HttpCredentials.Bearer -> "BEARER"
                        null -> "NONE"
                    },
                basicUsername = (connection.credentials as? com.jervis.entity.connection.HttpCredentials.Basic)?.username,
                basicPassword = (connection.credentials as? com.jervis.entity.connection.HttpCredentials.Basic)?.password,
                bearerToken = (connection.credentials as? com.jervis.entity.connection.HttpCredentials.Bearer)?.token,
                attachmentUrl = attachmentUrl,
            )

        // Download via service-atlassian (rate-limited)
        return atlassianClient.downloadJiraAttachment(
            // Not used by service-atlassian
            request = request,
        )
    }

    /**
     * Extract image dimensions from binary data.
     * Returns (width, height) or (null, null) if not an image.
     */
    private fun extractImageDimensions(
        binaryData: ByteArray,
        mimeType: String,
    ): Pair<Int?, Int?> {
        if (!mimeType.startsWith("image/")) {
            return Pair(null, null)
        }

        return try {
            val inputStream = java.io.ByteArrayInputStream(binaryData)
            val image = javax.imageio.ImageIO.read(inputStream)
            if (image != null) {
                Pair(image.width, image.height)
            } else {
                Pair(null, null)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract image dimensions: ${e.message}" }
            Pair(null, null)
        }
    }
}
