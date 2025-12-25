package com.jervis.service.indexing.jira

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.dto.atlassian.JiraAttachmentDownloadRequest
import com.jervis.common.dto.atlassian.JiraIssueRequest
import com.jervis.domain.PollingStatusEnum
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.entity.connection.ConnectionDocument.HttpCredentials
import com.jervis.entity.connection.basicPassword
import com.jervis.entity.connection.basicUsername
import com.jervis.entity.connection.bearerToken
import com.jervis.entity.connection.toAuthType
import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.service.background.TaskService
import com.jervis.service.connection.ConnectionService
import com.jervis.service.indexing.jira.state.JiraStateManager
import com.jervis.service.storage.DirectoryStructureService
import com.jervis.types.SourceUrn
import com.jervis.util.toAttachmentType
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
 * Continuous indexer for Jira issues.
 *
 * NEW ARCHITECTURE (Two-Phase Indexing):
 * - CentralPoller fetches MINIMAL data for change detection → stores in MongoDB as NEW
 * - This indexer:
 *   1. Reads NEW documents from MongoDB
 *   2. Fetches COMPLETE issue details from Jira API (getJiraIssue)
 *   3. Creates JIRA_PROCESSING PendingTask with full content
 * - KoogQualifierAgent (CPU) handles ALL structuring
 *
 * ETL Flow: MongoDB (NEW minimal) → API (full details) → JIRA_PROCESSING Task → KoogQualifierAgent → Graph + RAG
 */
@Service
@Profile("!cli")
@Order(10) // Start after WeaviateSchemaInitializer
class JiraContinuousIndexer(
    private val stateManager: JiraStateManager,
    private val taskService: TaskService,
    private val connectionService: ConnectionService,
    private val atlassianClient: IAtlassianClient,
    private val directoryStructureService: DirectoryStructureService,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting JiraContinuousIndexer (MongoDB → Weaviate)..." }
        scope.launch {
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "Continuous indexer crashed" } }
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
        if (doc.status != PollingStatusEnum.NEW) {
            logger.warn { "Received non-NEW document for indexing: ${doc.issueKey} (state=${doc.status})" }
            return
        }

        logger.debug { "Processing Jira issue ${doc.issueKey} (${doc.summary})" }

        try {
            // Fetch complete issue details from Jira API
            val connection = connectionService.findById(doc.connectionId)
            require(connection?.connectionType == ConnectionDocument.ConnectionTypeEnum.HTTP) {
                "Connection ${doc.connectionId} is not an HTTP connection"
            }

            val credentials = connection.credentials
            require(credentials is HttpCredentials) {
                "Connection ${doc.connectionId} does not have HTTP credentials"
            }

            val issueRequest =
                JiraIssueRequest(
                    baseUrl = connection.baseUrl,
                    authType =
                        when (credentials) {
                            is HttpCredentials.Basic -> "BASIC"
                            is HttpCredentials.Bearer -> "BEARER"
                        },
                    basicUsername =
                        when (credentials) {
                            is HttpCredentials.Basic -> credentials.username
                            else -> null
                        },
                    basicPassword =
                        when (credentials) {
                            is HttpCredentials.Basic -> credentials.password
                            else -> null
                        },
                    bearerToken =
                        when (credentials) {
                            is HttpCredentials.Bearer -> credentials.token
                            else -> null
                        },
                    issueKey = doc.issueKey,
                )

            val issueDetails = atlassianClient.getJiraIssue(issueRequest)

            val descriptionText = issueDetails.renderedDescription ?: ""
            val issueContent =
                buildString {
                    append("# ${doc.issueKey}: ${issueDetails.fields.summary ?: doc.summary}\n\n")

                    if (issueDetails.fields.status != null) {
                        append("**Status:** ${issueDetails.fields.status!!.name}\n")
                    }
                    if (issueDetails.fields.priority != null) {
                        append("**Priority:** ${issueDetails.fields.priority!!.name}\n")
                    }
                    if (issueDetails.fields.assignee != null) {
                        append("**Assignee:** ${issueDetails.fields.assignee!!.displayName}\n")
                    }
                    if (issueDetails.fields.reporter != null) {
                        append("**Reporter:** ${issueDetails.fields.reporter!!.displayName}\n")
                    }
                    if (issueDetails.fields.created != null) {
                        append("**Created:** ${issueDetails.fields.created}\n")
                    }
                    if (issueDetails.fields.updated != null) {
                        append("**Updated:** ${issueDetails.fields.updated}\n")
                    }
                    append("\n")

                    if (descriptionText.isNotBlank()) {
                        append("## Description\n\n")
                        append(descriptionText)
                        append("\n\n")
                    }

                    // Add metadata for qualifier
                    append("## Document Metadata\n")
                    append("- **Source:** Jira Issue\n")
                    append("- **Document ID:** jira:${doc.issueKey}\n")
                    append("- **Connection ID:** ${doc.connectionId}\n")
                    append("- **Issue Key:** ${doc.issueKey}\n")
                }

            val attachmentMetadata: List<AttachmentMetadata> =
                issueDetails.fields.attachments
                    ?.mapNotNull { attachment ->
                        runCatching {
                            val downloadUrl =
                                attachment.content
                                    ?.takeIf { it.isNotBlank() }
                                    ?: return@runCatching null.also {
                                        logger.warn { "Jira attachment ${attachment.id} has no download URL, skipping" }
                                    }

                            val request =
                                JiraAttachmentDownloadRequest(
                                    baseUrl = connection.baseUrl,
                                    authType = credentials.toAuthType(),
                                    basicUsername = credentials.basicUsername(),
                                    basicPassword = credentials.basicPassword(),
                                    bearerToken = credentials.bearerToken(),
                                    attachmentUrl = downloadUrl,
                                )

                            val binaryData =
                                atlassianClient
                                    .downloadJiraAttachment(request)
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: return@runCatching null.also {
                                        logger.warn { "Failed to download Jira attachment ${attachment.id}, skipping" }
                                    }

                            val storagePath =
                                directoryStructureService.storeAttachment(
                                    clientId = doc.clientId,
                                    filename = attachment.filename,
                                    binaryData = binaryData,
                                )

                            AttachmentMetadata(
                                id = attachment.id,
                                filename = attachment.filename,
                                mimeType = attachment.mimeType ?: "application/octet-stream",
                                sizeBytes = attachment.size ?: binaryData.size.toLong(),
                                storagePath = storagePath,
                                type = attachment.mimeType.toAttachmentType(),
                                visionAnalysis = null,
                            )
                        }.getOrElse { e ->
                            logger.error(e) {
                                "Failed to download/store Jira attachment ${attachment.id}: ${e.message}"
                            }
                            null
                        }
                    }
                    ?: emptyList()

            // Create JIRA_PROCESSING task with attachments
            taskService.createTask(
                taskType = TaskTypeEnum.JIRA_PROCESSING,
                content = issueContent,
                projectId = doc.projectId,
                clientId = doc.clientId,
                correlationId = "jira:${doc.issueKey}",
                sourceUrn =
                    SourceUrn.jira(
                        connectionId = doc.connectionId.value,
                        issueKey = doc.issueKey,
                    ),
                attachments = attachmentMetadata,
            )

            // Convert to INDEXED state - delete full content, keep minimal tracking
            stateManager.markAsIndexed(doc)

            logger.info { "Created JIRA_PROCESSING task for Jira issue: ${doc.issueKey}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task for Jira issue ${doc.issueKey}" }
            stateManager.markAsFailed(doc, "Task creation failed: ${e.message}")
        }
    }
}
