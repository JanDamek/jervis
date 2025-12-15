package com.jervis.service.jira

import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.service.background.PendingTaskService
import com.jervis.service.indexing.status.IndexingStatusRegistry
import com.jervis.service.jira.state.JiraStateManager
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
 * Continuous indexer for Jira issues.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - CentralPoller fetches FULL data from API → stores in MongoDB as NEW
 * - This indexer reads NEW documents from MongoDB (NO API CALLS)
 */
@Service
@Order(10) // Start after WeaviateSchemaInitializer
class JiraContinuousIndexer(
    private val stateManager: JiraStateManager,
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
        logger.info { "Starting JiraContinuousIndexer (MongoDB → Weaviate)..." }
        scope.launch {
            kotlin.runCatching {
                indexingRegistry.start(
                    com.jervis.service.indexing.status.IndexingStatusRegistry.ToolStateEnum.JIRA,
                    displayName = "Atlassian (Jira)",
                    message = "Starting continuous Jira indexing from MongoDB",
                )
            }
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "Continuous indexer crashed" } }
                .also {
                    kotlin.runCatching {
                        indexingRegistry.finish(
                            com.jervis.service.indexing.status.IndexingStatusRegistry.ToolStateEnum.JIRA,
                            message = "Jira indexer stopped",
                        )
                    }
                }
        }
    }

    private suspend fun indexContinuously() {
        // Continuous flow of NEW issues from MongoDB
        stateManager.continuousNewIssuesAllAccounts().collect { doc ->
            try {
                indexIssue(doc)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index Jira issue ${doc.issueKey}" }
                stateManager.markAsFailed(doc, "Indexing error: ${e.message}")
            }
        }
    }

    private suspend fun indexIssue(doc: JiraIssueIndexDocument) {
        // Only process NEW documents (type-safe check)
        if (doc !is JiraIssueIndexDocument.New) {
            logger.warn { "Received non-NEW document for indexing: ${doc.issueKey} (state=${doc.state})" }
            return
        }

        logger.debug { "Processing Jira issue ${doc.issueKey} (${doc.summary})" }

        try {
            // Clean Jira description through Tika (removes HTML/Jira markup)
            val cleanDescription =
                if (!doc.description.isNullOrBlank()) {
                    tikaTextExtractionService.extractPlainText(
                        content = doc.description,
                        fileName = "jira-${doc.issueKey}.html",
                    )
                } else {
                    ""
                }

            // Clean comments through Tika as well
            val cleanComments =
                doc.comments.map { comment ->
                    comment.copy(
                        body =
                            tikaTextExtractionService.extractPlainText(
                                content = comment.body,
                                fileName = "jira-comment.html",
                            ),
                    )
                }

            // Build Jira issue content for task
            val issueContent =
                buildString {
                    append("# ${doc.issueKey}: ${doc.summary}\n\n")
                    append("**Type:** ${doc.issueType}\n")
                    append("**Status:** ${doc.status}\n")
                    append("**Priority:** ${doc.priority ?: "N/A"}\n")
                    if (!doc.assignee.isNullOrBlank()) {
                        append("**Assignee:** ${doc.assignee}\n")
                    }
                    if (!doc.reporter.isNullOrBlank()) {
                        append("**Reporter:** ${doc.reporter}\n")
                    }
                    append("**Created:** ${doc.createdAt}\n")
                    append("**Updated:** ${doc.jiraUpdatedAt}\n")
                    append("\n")

                    if (cleanDescription.isNotBlank()) {
                        append("## Description\n\n")
                        append(cleanDescription)
                        append("\n\n")
                    }

                    if (doc.labels.isNotEmpty()) {
                        append("**Labels:** ${doc.labels.joinToString(", ")}\n\n")
                    }

                    // Add comments
                    if (cleanComments.isNotEmpty()) {
                        append("## Comments\n\n")
                        cleanComments.forEachIndexed { index, comment ->
                            append("### Comment ${index + 1} by ${comment.author}\n")
                            append("**Created:** ${comment.created}\n\n")
                            append(comment.body)
                            append("\n\n")
                        }
                    }

                    // Add attachments list
                    if (doc.attachments.isNotEmpty()) {
                        append("## Attachments\n")
                        doc.attachments.forEach { att ->
                            append("- ${att.filename} (${att.mimeType}, ${att.size} bytes)\n")
                        }
                        append("\n")
                    }

                    // Add metadata for qualifier
                    append("## Document Metadata\n")
                    append("- **Source:** Jira Issue\n")
                    append("- **Document ID:** jira:${doc.issueKey}\n")
                    append("- **Connection ID:** ${doc.connectionDocumentId}\n")
                    append("- **Issue Key:** ${doc.issueKey}\n")
                    append("- **Comment Count:** ${doc.comments.size}\n")
                    append("- **Attachment Count:** ${doc.attachments.size}\n")
                }

            // Process attachments for vision analysis
            val attachmentMetadata = processAttachments(doc)

            if (attachmentMetadata.isNotEmpty()) {
                logger.info { "Processed ${attachmentMetadata.size} attachments for vision analysis: ${doc.issueKey}" }
            }

            // Create JIRA_PROCESSING task with attachments
            pendingTaskService.createTask(
                taskType = PendingTaskTypeEnum.JIRA_PROCESSING,
                content = issueContent,
                projectId = doc.projectId,
                clientId = doc.clientId,
                correlationId = "jira:${doc.issueKey}",
                sourceUrn =
                    SourceUrn.jira(
                        connectionId = doc.connectionDocumentId.value,
                        issueKey = doc.issueKey,
                    ),
                attachments = attachmentMetadata,
            )

            // Convert to INDEXED state - delete full content, keep minimal tracking
            stateManager.markAsIndexed(doc)

            // Report progress
            kotlin.runCatching {
                indexingRegistry.ensureTool(
                    com.jervis.service.indexing.status.IndexingStatusRegistry.ToolStateEnum.JIRA,
                    displayName = "Atlassian (Jira)",
                )
                indexingRegistry.progress(
                    com.jervis.service.indexing.status.IndexingStatusRegistry.ToolStateEnum.JIRA,
                    processedInc = 1,
                    message = "Created task for ${doc.issueKey}",
                )
            }

            logger.info { "Created JIRA_PROCESSING task for Jira issue: ${doc.issueKey}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task for Jira issue ${doc.issueKey}" }
            stateManager.markAsFailed(doc, "Task creation failed: ${e.message}")
        }
    }

    /**
     * Download and store Jira attachments for vision analysis.
     * Only downloads image attachments (vision model input).
     */
    private suspend fun processAttachments(
        doc: JiraIssueIndexDocument.New,
    ): List<com.jervis.entity.AttachmentMetadata> {
        val attachmentMetadataList = mutableListOf<com.jervis.entity.AttachmentMetadata>()

        // Filter attachments that should be processed with vision
        val visualAttachments =
            doc.attachments.filter { att ->
                val type = com.jervis.entity.classifyAttachmentType(att.mimeType)
                type == com.jervis.entity.AttachmentType.IMAGE ||
                    type == com.jervis.entity.AttachmentType.PDF_SCANNED
            }

        if (visualAttachments.isEmpty()) {
            return emptyList()
        }

        logger.debug { "Processing ${visualAttachments.size} visual attachments for ${doc.issueKey}" }

        // FAIL-FAST: If any attachment download/storage fails, exception propagates
        // This marks the entire Jira issue as FAILED for retry
        for (att in visualAttachments) {
            // Download attachment from Jira via service-atlassian (rate-limited)
            val binaryData = downloadAttachment(att.downloadUrl, doc)

            // Get image dimensions for token estimation (if image)
            val (width, height) = extractImageDimensions(binaryData, att.mimeType)

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
                    widthPixels = width,
                    heightPixels = height,
                    visionAnalysis = null, // Populated later by Qualifier
                )

            attachmentMetadataList.add(metadata)
            logger.debug { "Stored attachment: ${att.filename} (${att.mimeType}, ${att.size} bytes) → $storagePath" }
        }

        return attachmentMetadataList
    }

    /**
     * Download attachment binary data from Jira URL via service-atlassian.
     * Uses rate-limited API client to respect Atlassian rate limits.
     */
    private suspend fun downloadAttachment(
        attachmentUrl: String,
        doc: JiraIssueIndexDocument.New,
    ): ByteArray {
        // Load connection to get auth credentials
        val connection = connectionService.findById(doc.connectionDocumentId.value)
            ?: throw IllegalStateException("Connection not found: ${doc.connectionDocumentId}")

        if (connection !is com.jervis.entity.connection.ConnectionDocument.HttpConnectionDocument) {
            throw IllegalStateException("Connection is not HTTP: ${connection::class.simpleName}")
        }

        // Build request with auth credentials
        val request = com.jervis.common.dto.atlassian.JiraAttachmentDownloadRequest(
            baseUrl = connection.baseUrl,
            authType = when (connection.credentials) {
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
            connection = "", // Not used by service-atlassian
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
