package com.jervis.bugtracker.internal.indexing

import com.jervis.common.client.IAtlassianClient
import com.jervis.common.client.IBugTrackerClient
import com.jervis.common.dto.AuthType
import com.jervis.common.dto.atlassian.JiraAttachmentDownloadRequest
import com.jervis.common.dto.atlassian.JiraIssueRequest
import com.jervis.common.dto.bugtracker.BugTrackerGetCommentsRequest
import com.jervis.common.dto.bugtracker.BugTrackerGetCommentsResponse
import com.jervis.common.dto.bugtracker.BugTrackerSearchRequest
import com.jervis.common.types.SourceUrn
import com.jervis.infrastructure.polling.PollingStatusEnum
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.dto.connection.ProviderEnum
import com.jervis.connection.ConnectionDocument
import com.jervis.bugtracker.internal.entity.BugTrackerIssueIndexDocument
import com.jervis.bugtracker.internal.state.BugTrackerStateManager
import com.jervis.task.TaskService
import com.jervis.connection.ConnectionService
import com.jervis.infrastructure.indexing.AttachmentKbIndexingService
import com.jervis.infrastructure.storage.DirectoryStructureService
import com.jervis.util.toAttachmentType
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
 * Continuous indexer for bug tracker issues (Jira, GitHub Issues, GitLab Issues, etc.).
 *
 * Two-Phase Indexing:
 * - CentralPoller fetches MINIMAL data for change detection → stores in MongoDB as NEW
 * - This indexer:
 *   1. Reads NEW documents from MongoDB
 *   2. Fetches COMPLETE issue details from provider-specific API
 *   3. Creates BUGTRACKER_PROCESSING PendingTask with full content
 * - Qualifier (CPU) handles ALL structuring
 *
 * Provider-specific logic:
 * - Atlassian: fetches full issue details + attachments via IAtlassianClient
 * - GitHub: fetches issue details via IBugTrackerClient (githubBugTrackerClient)
 */
@Service
@Order(10)
class BugTrackerContinuousIndexer(
    private val stateManager: BugTrackerStateManager,
    private val taskService: TaskService,
    private val connectionService: ConnectionService,
    private val atlassianClient: IAtlassianClient,
    private val githubBugTrackerClient: IBugTrackerClient,
    private val gitlabBugTrackerClient: IBugTrackerClient,
    private val directoryStructureService: DirectoryStructureService,
    private val attachmentKbIndexingService: AttachmentKbIndexingService,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting BugTrackerContinuousIndexer (MongoDB → Weaviate)..." }
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
                logger.error(e) { "Failed to index issue ${doc.issueKey}" }
                stateManager.markAsFailed(doc, "Indexing error: ${e.message}")
            }
        }
    }

    private suspend fun indexIssue(doc: BugTrackerIssueIndexDocument) {
        if (doc.status != PollingStatusEnum.NEW) {
            logger.warn { "Received non-NEW document for indexing: ${doc.issueKey} (state=${doc.status})" }
            return
        }

        val connection = connectionService.findById(doc.connectionId)
        if (connection == null) {
            logger.warn { "Connection ${doc.connectionId} not found for issue ${doc.issueKey} — skipping (stale reference)" }
            return
        }

        when (connection.provider) {
            ProviderEnum.GITHUB -> indexGitHubIssue(doc, connection)
            ProviderEnum.GITLAB -> indexGitLabIssue(doc, connection)
            else -> indexAtlassianIssue(doc, connection)
        }
    }

    private suspend fun indexGitHubIssue(doc: BugTrackerIssueIndexDocument, connection: ConnectionDocument) {
        logger.debug { "Processing GitHub issue ${doc.issueKey} (${doc.summary})" }

        try {
            // Parse issueKey "owner/repo#123" to get repo and issue number
            val repoKey = doc.issueKey.substringBeforeLast("#")
            val issueNumber = doc.issueKey.substringAfterLast("#")

            // Fetch full issue data from GitHub API
            val searchRequest = BugTrackerSearchRequest(
                baseUrl = connection.baseUrl,
                authType = AuthType.BEARER,
                bearerToken = connection.bearerToken,
                projectKey = repoKey,
            )

            val response = githubBugTrackerClient.searchIssues(searchRequest)
            val issue = response.issues.find { it.key == "#$issueNumber" }

            // Fetch comments (outside buildString so they're accessible for mention detection)
            val commentsResponse = fetchComments(
                client = githubBugTrackerClient,
                connection = connection,
                issueKey = "#$issueNumber",
                projectKey = repoKey,
                label = doc.issueKey,
            )

            val issueContent = buildString {
                append("# ${doc.issueKey}: ${issue?.title ?: doc.summary}\n\n")

                if (issue != null) {
                    append("**Status:** ${issue.status}\n")
                    if (issue.assignee != null) append("**Assignee:** ${issue.assignee}\n")
                    if (issue.reporter != null) append("**Reporter:** ${issue.reporter}\n")
                    append("**Created:** ${issue.created}\n")
                    append("**Updated:** ${issue.updated}\n")
                    if (issue.url.isNotBlank()) append("**URL:** ${issue.url}\n")
                    append("\n")

                    if (!issue.description.isNullOrBlank()) {
                        append("## Description\n\n")
                        append(issue.description)
                        append("\n\n")
                    }
                }

                if (commentsResponse.comments.isNotEmpty()) {
                    append("## Comments\n\n")
                    for (comment in commentsResponse.comments) {
                        append("**${comment.author}** (${comment.created}):\n")
                        append(comment.body)
                        append("\n\n")
                    }
                }

                append("## Document Metadata\n")
                append("- **Source:** GitHub Issue\n")
                append("- **Repository:** $repoKey\n")
                append("- **Document ID:** github:${doc.issueKey}\n")
                append("- **Connection ID:** ${doc.connectionId}\n")
                append("- **Issue Key:** ${doc.issueKey}\n")
            }

            // Detect if Jervis is @mentioned in any comment
            val mentionsJervis = detectMentionsJervis(
                connection = connection,
                commentBodies = commentsResponse.comments.map { it.body },
            )

            taskService.createTask(
                taskType = TaskTypeEnum.BUGTRACKER_PROCESSING,
                content = issueContent,
                projectId = doc.projectId,
                clientId = doc.clientId,
                correlationId = "github:${doc.issueKey}",
                sourceUrn = SourceUrn.githubIssue(
                    connectionId = doc.connectionId.value,
                    issueKey = doc.issueKey,
                ),
                taskName = "${doc.issueKey}: ${(issue?.title ?: doc.summary ?: doc.issueKey).take(100)}",
                mentionsJervis = mentionsJervis,
            )

            stateManager.markAsIndexed(doc)
            logger.info { "Created BUGTRACKER_PROCESSING task for GitHub issue: ${doc.issueKey}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task for GitHub issue ${doc.issueKey}" }
            stateManager.markAsFailed(doc, "Task creation failed: ${e.message}")
        }
    }

    private suspend fun indexGitLabIssue(doc: BugTrackerIssueIndexDocument, connection: ConnectionDocument) {
        logger.debug { "Processing GitLab issue ${doc.issueKey} (${doc.summary})" }

        try {
            val projectKey = doc.issueKey.substringBeforeLast("#")
            val issueNumber = doc.issueKey.substringAfterLast("#")

            val searchRequest = BugTrackerSearchRequest(
                baseUrl = connection.baseUrl,
                authType = AuthType.BEARER,
                bearerToken = connection.bearerToken,
                projectKey = projectKey,
            )

            val response = gitlabBugTrackerClient.searchIssues(searchRequest)
            val issue = response.issues.find { it.key == "#$issueNumber" }

            // Fetch comments (outside buildString so they're accessible for mention detection)
            val commentsResponse = fetchComments(
                client = gitlabBugTrackerClient,
                connection = connection,
                issueKey = "$projectKey#$issueNumber",
                projectKey = projectKey,
                label = doc.issueKey,
            )

            val issueContent = buildString {
                append("# ${doc.issueKey}: ${issue?.title ?: doc.summary}\n\n")

                if (issue != null) {
                    append("**Status:** ${issue.status}\n")
                    if (issue.assignee != null) append("**Assignee:** ${issue.assignee}\n")
                    if (issue.reporter != null) append("**Reporter:** ${issue.reporter}\n")
                    append("**Created:** ${issue.created}\n")
                    append("**Updated:** ${issue.updated}\n")
                    if (issue.url.isNotBlank()) append("**URL:** ${issue.url}\n")
                    append("\n")

                    if (!issue.description.isNullOrBlank()) {
                        append("## Description\n\n")
                        append(issue.description)
                        append("\n\n")
                    }
                }

                if (commentsResponse.comments.isNotEmpty()) {
                    append("## Comments\n\n")
                    for (comment in commentsResponse.comments) {
                        append("**${comment.author}** (${comment.created}):\n")
                        append(comment.body)
                        append("\n\n")
                    }
                }

                append("## Document Metadata\n")
                append("- **Source:** GitLab Issue\n")
                append("- **Project:** $projectKey\n")
                append("- **Document ID:** gitlab:${doc.issueKey}\n")
                append("- **Connection ID:** ${doc.connectionId}\n")
                append("- **Issue Key:** ${doc.issueKey}\n")
            }

            // Detect if Jervis is @mentioned in any comment
            val mentionsJervis = detectMentionsJervis(
                connection = connection,
                commentBodies = commentsResponse.comments.map { it.body },
            )

            taskService.createTask(
                taskType = TaskTypeEnum.BUGTRACKER_PROCESSING,
                content = issueContent,
                projectId = doc.projectId,
                clientId = doc.clientId,
                correlationId = "gitlab:${doc.issueKey}",
                sourceUrn = SourceUrn.gitlabIssue(
                    connectionId = doc.connectionId.value,
                    issueKey = doc.issueKey,
                ),
                taskName = "${doc.issueKey}: ${(issue?.title ?: doc.summary ?: doc.issueKey).take(100)}",
                mentionsJervis = mentionsJervis,
            )

            stateManager.markAsIndexed(doc)
            logger.info { "Created BUGTRACKER_PROCESSING task for GitLab issue: ${doc.issueKey}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task for GitLab issue ${doc.issueKey}" }
            stateManager.markAsFailed(doc, "Task creation failed: ${e.message}")
        }
    }

    private suspend fun indexAtlassianIssue(doc: BugTrackerIssueIndexDocument, connection: ConnectionDocument) {
        logger.debug { "Processing Atlassian issue ${doc.issueKey} (${doc.summary})" }

        try {
            val issueRequest =
                JiraIssueRequest(
                    baseUrl = connection.baseUrl,
                    authType = AuthType.valueOf(connection.authType.name),
                    basicUsername = connection.username,
                    basicPassword = connection.password,
                    bearerToken = connection.bearerToken,
                    cloudId = connection.cloudId,
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

                    // Embed Jira comments (returned in the issue response)
                    val jiraComments = issueDetails.comments ?: emptyList()
                    if (jiraComments.isNotEmpty()) {
                        append("## Comments\n\n")
                        for (comment in jiraComments) {
                            val authorName = comment.author?.displayName ?: "unknown"
                            val bodyText = comment.body?.toString()?.let { raw ->
                                // ADF is a JSON structure — extract text content for display
                                if (raw.startsWith("{") || raw.startsWith("[")) {
                                    // Rough extraction: strip JSON structure, keep text content
                                    raw.replace(Regex("""[{}\[\]":]"""), " ")
                                        .replace(Regex("""\s+"""), " ")
                                        .trim()
                                        .take(5000)
                                } else {
                                    // Plain text string (with surrounding quotes from JsonElement)
                                    raw.trim('"').take(5000)
                                }
                            } ?: ""
                            append("**$authorName** (${comment.created ?: ""}):\n")
                            append(bodyText)
                            append("\n\n")
                        }
                    }

                    // Add metadata for qualifier
                    append("## Document Metadata\n")
                    append("- **Source:** Atlassian Jira Issue\n")
                    append("- **Document ID:** jira:${doc.issueKey}\n")
                    append("- **Connection ID:** ${doc.connectionId}\n")
                    append("- **Issue Key:** ${doc.issueKey}\n")
                }

            // Detect if Jervis is @mentioned in Jira comments
            val jiraCommentBodies = (issueDetails.comments ?: emptyList()).map { comment ->
                comment.body?.toString() ?: ""
            }
            val mentionsJervis = detectMentionsJervis(
                connection = connection,
                commentBodies = jiraCommentBodies,
            )

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
                                    authType = AuthType.valueOf(connection.authType.name),
                                    basicUsername = connection.username,
                                    basicPassword = connection.password,
                                    bearerToken = connection.bearerToken,
                                    cloudId = connection.cloudId,
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

            // Create BUGTRACKER_PROCESSING task with attachments
            taskService.createTask(
                taskType = TaskTypeEnum.BUGTRACKER_PROCESSING,
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
                taskName = "${doc.issueKey}: ${(issueDetails.fields.summary ?: doc.summary ?: doc.issueKey).take(100)}",
                mentionsJervis = mentionsJervis,
            )

            // Index Jira attachments as KB documents for full-text search and RAG
            val issueSummary = issueDetails.fields.summary ?: doc.summary ?: doc.issueKey
            for (att in attachmentMetadata) {
                try {
                    attachmentKbIndexingService.indexStoredAttachmentAsKbDocument(
                        clientId = doc.clientId,
                        projectId = doc.projectId,
                        filename = att.filename,
                        mimeType = att.mimeType,
                        sizeBytes = att.sizeBytes,
                        existingStoragePath = att.storagePath,
                        sourceUrn = SourceUrn.jiraAttachment(
                            connectionId = doc.connectionId.value,
                            issueKey = doc.issueKey,
                            filename = att.filename,
                        ),
                        title = "Jira ${doc.issueKey}: ${att.filename}",
                        description = "Attachment from Jira issue '${doc.issueKey}: $issueSummary'",
                        tags = listOf("jira-attachment", "jira", doc.issueKey),
                    )
                } catch (e: Exception) {
                    logger.warn(e) {
                        "Failed to index Jira attachment as KB document: ${att.filename} from ${doc.issueKey}"
                    }
                }
            }

            // Convert to INDEXED state - delete full content, keep minimal tracking
            stateManager.markAsIndexed(doc)

            logger.info { "Created BUGTRACKER_PROCESSING task for Atlassian issue: ${doc.issueKey}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task for Atlassian issue ${doc.issueKey}" }
            stateManager.markAsFailed(doc, "Task creation failed: ${e.message}")
        }
    }

    /**
     * Detect if Jervis (@selfUsername) is mentioned in any comment body.
     * Returns true if connection has a selfUsername and it appears as @mention in any comment.
     */
    private fun detectMentionsJervis(
        connection: ConnectionDocument,
        commentBodies: List<String>,
    ): Boolean {
        val selfUsername = connection.selfUsername ?: return false
        if (commentBodies.isEmpty()) return false

        val mentioned = commentBodies.any { body ->
            body.contains("@$selfUsername", ignoreCase = true)
        }

        if (mentioned) {
            logger.info { "Detected @$selfUsername mention in comments (connection=${connection.id})" }
        }

        return mentioned
    }

    /**
     * Fetch comments for an issue from the appropriate bug tracker client.
     * Returns empty response on failure (non-blocking — issue content is still indexed).
     */
    private suspend fun fetchComments(
        client: IBugTrackerClient,
        connection: ConnectionDocument,
        issueKey: String,
        projectKey: String?,
        label: String,
    ): BugTrackerGetCommentsResponse {
        return runCatching {
            client.getComments(
                BugTrackerGetCommentsRequest(
                    baseUrl = connection.baseUrl,
                    authType = AuthType.valueOf(connection.authType.name),
                    bearerToken = connection.bearerToken,
                    basicUsername = connection.username,
                    basicPassword = connection.password,
                    cloudId = connection.cloudId,
                    issueKey = issueKey,
                    projectKey = projectKey,
                ),
            )
        }.getOrElse { e ->
            logger.warn(e) { "Failed to fetch comments for $label, continuing without comments" }
            BugTrackerGetCommentsResponse(emptyList())
        }
    }
}
