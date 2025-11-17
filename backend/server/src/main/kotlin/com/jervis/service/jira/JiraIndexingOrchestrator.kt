package com.jervis.service.jira

import com.jervis.domain.jira.JiraProjectKey
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.repository.mongo.JiraConnectionMongoRepository
import com.jervis.repository.mongo.JiraIssueIndexMongoRepository
import com.jervis.service.link.LinkIndexingService
import com.jervis.service.rag.RagIndexingService
import com.jervis.service.text.TextChunkingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Instant

@Service
class JiraIndexingOrchestrator(
    private val selection: JiraSelectionService,
    private val api: JiraApiClient,
    private val auth: JiraAuthService,
    private val ragIndexingService: RagIndexingService,
    private val connectionRepository: JiraConnectionMongoRepository,
    private val issueIndexRepository: JiraIssueIndexMongoRepository,
    private val textChunkingService: TextChunkingService,
    private val linkIndexingService: LinkIndexingService,
    private val jiraAttachmentIndexer: JiraAttachmentIndexer,
    private val configCache: com.jervis.service.cache.ClientProjectConfigCache,
    private val connectionService: JiraConnectionService,
    private val indexingRegistry: com.jervis.service.indexing.status.IndexingStatusRegistry,
    private val errorLogService: com.jervis.service.error.ErrorLogService,
) {
    private val logger = KotlinLogging.logger {}

    /** Entry point for Jira indexing for a client. Safe to call from scheduler. */
    suspend fun indexClient(clientId: ObjectId) =
        withContext(Dispatchers.IO) {
            val runReason = "Indexing JIRA for client=${clientId.toHexString()}"
            kotlin.runCatching { indexingRegistry.start("jira", displayName = "Atlassian (Jira)", message = runReason) }

            try {
                logger.info { "JIRA_INDEX: Start for client=${clientId.toHexString()}" }

                // Ensure we have a connection and it's VALID before proceeding
                val connectionDoc = connectionRepository.findByClientId(clientId)
                if (connectionDoc == null) {
                    logger.warn { "JIRA_INDEX: No Jira connection configured for client=${clientId.toHexString()}" }
                    runCatching { indexingRegistry.info("jira", "No Jira connection configured for client=${clientId.toHexString()}") }
                    return@withContext
                }
                if (connectionDoc.authStatus != "VALID") {
                    logger.warn {
                        "JIRA_INDEX: Skipping client=${clientId.toHexString()} due to authStatus=${connectionDoc.authStatus}. Use Test Connection to enable."
                    }
                    runCatching {
                        indexingRegistry.info(
                            "jira",
                            "Skipping: authStatus=${connectionDoc.authStatus} for client=${clientId.toHexString()}",
                        )
                    }
                    return@withContext
                }

                // Ensure token is valid; if ensureValidToken throws a WebClientResponseException 401/403, mark invalid and stop
                try {
                    selection.getConnection(clientId).let { auth.ensureValidToken(it) }
                } catch (e: Exception) {
                    if (isAuthError(e)) {
                        val doc = connectionRepository.findByClientId(clientId)
                        if (doc != null) {
                            runCatching { connectionService.markAuthInvalid(doc, e.message) }
                        }
                        logger.warn(
                            e,
                        ) { "JIRA_INDEX: Auth check failed for client=${clientId.toHexString()}, marking INVALID and skipping" }
                        runCatching { indexingRegistry.info("jira", "Auth check failed, skipping client=${clientId.toHexString()}") }
                        // Persist auth-related error
                        runCatching { errorLogService.recordError(e, clientId = clientId) }
                        return@withContext
                    } else {
                        // Persist non-auth error then rethrow to be handled above
                        runCatching { errorLogService.recordError(e, clientId = clientId) }
                        throw e
                    }
                }

                // Try to load selections (primary project, preferred user). If missing, continue with client-level indexing.
                val selections: Pair<JiraProjectKey, com.jervis.domain.jira.JiraAccountId>? =
                    try {
                        selection.ensureSelectionsOrCreateTasks(clientId)
                    } catch (e: Exception) {
                        logger.warn { "JIRA_INDEX: Missing selections for client=${clientId.toHexString()} - ${e.message}. Falling back to GLOBAL (all projects) indexing." }
                        runCatching {
                            indexingRegistry.info(
                                "jira",
                                "Selections missing → fallback to client-level indexing (all projects) for client=${clientId.toHexString()}",
                            )
                        }
                        runCatching { errorLogService.recordError(e, clientId = clientId) }
                        null
                    }
                val primaryProject: JiraProjectKey? = selections?.first
                var me: com.jervis.domain.jira.JiraAccountId? = selections?.second

                // Build mapping: Jira project key → Jervis projectId
                // Uses in-memory cache for instant access, no DB roundtrip
                val jiraProjectMapping: Map<String, ObjectId> =
                    try {
                        val projects = configCache.getProjectsForClient(clientId)
                        projects
                            .mapNotNull { project ->
                                project.overrides?.jiraProjectKey?.let { jiraKey ->
                                    jiraKey to project.id
                                }
                            }.toMap()
                    } catch (e: Exception) {
                        logger.warn(e) { "JIRA_INDEX: Failed to load Jira project mapping from cache for client=${clientId.toHexString()}" }
                        runCatching { indexingRegistry.error("jira", "Failed to load Jira project mapping: ${e.message}") }
                        runCatching { errorLogService.recordError(e, clientId = clientId) }
                        emptyMap()
                    }
                runCatching { indexingRegistry.info("jira", "Loaded Jira project mapping size=${jiraProjectMapping.size}") }

                // Fetch ALL Jira projects for this client (not just mapped ones)
                val connValid =
                    try {
                        selection.getConnection(clientId).let { auth.ensureValidToken(it) }
                    } catch (e: Exception) {
                        if (isAuthError(e)) {
                            val doc = connectionRepository.findByClientId(clientId)
                            if (doc != null) {
                                runCatching { connectionService.markAuthInvalid(doc, e.message) }
                            }
                            logger.warn(
                                e,
                            ) {
                                "JIRA_INDEX: Auth check failed while listing projects for client=${clientId.toHexString()}, marking INVALID and skipping"
                            }
                            runCatching {
                                indexingRegistry.info(
                                    "jira",
                                    "Auth check failed while listing projects, skipping client=${clientId.toHexString()}",
                                )
                            }
                            runCatching { errorLogService.recordError(e, clientId = clientId) }
                            return@withContext
                        } else {
                            runCatching { errorLogService.recordError(e, clientId = clientId) }
                            throw e
                        }
                    }

                // If preferred user is not configured, try to auto-detect current user; if that fails, proceed without it
                if (me == null) {
                    me = runCatching { api.getMyself(connValid) }.onFailure { e ->
                        logger.info { "JIRA_INDEX: Unable to auto-detect preferred user for client=${clientId.toHexString()}: ${e.message}" }
                    }.getOrNull()
                }

                val allJiraProjects: List<JiraProjectKey> =
                    try {
                        api.listProjects(connValid).map { (key, _) -> key }.also { list ->
                            runCatching {
                                indexingRegistry.info(
                                    "jira",
                                    "Found ${list.size} Jira projects for client=${clientId.toHexString()}",
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // If this looks like an auth error, mark INVALID to stop further attempts until user fixes it
                        if (isAuthError(e)) {
                            val doc = connectionRepository.findByClientId(clientId)
                            if (doc != null) {
                                runCatching { connectionService.markAuthInvalid(doc, e.message) }
                            }
                            logger.warn(
                                e,
                            ) { "JIRA_INDEX: Failed to list all Jira projects for client=${clientId.toHexString()} due to auth error" }
                            runCatching {
                                indexingRegistry.error(
                                    "jira",
                                    "Failed to list Jira projects (auth): ${e.message}.",
                                )
                            }
                            runCatching { errorLogService.recordError(e, clientId = clientId) }
                            emptyList()
                        } else {
                            logger.warn(
                                e,
                            ) {
                                "JIRA_INDEX: Failed to list all Jira projects for client=${clientId.toHexString()}"
                            }
                            runCatching {
                                indexingRegistry.info(
                                    "jira",
                                    "Failed to list Jira projects: ${e.message}",
                                )
                            }
                            runCatching { errorLogService.recordError(e, clientId = clientId) }
                            emptyList()
                        }
                    }

                val projectsToIndex: List<JiraProjectKey> =
                    if (allJiraProjects.isNotEmpty()) allJiraProjects
                    else primaryProject?.let { single ->
                        logger.info { "JIRA_INDEX: Proceeding with configured primary project=${single.value} only" }
                        listOf(single)
                    } ?: emptyList()

                if (projectsToIndex.isEmpty()) {
                    logger.warn { "JIRA_INDEX: No Jira projects available to index for client=${clientId.toHexString()}" }
                    return@withContext
                }

                // Determine incremental window based on last sync
                val latestConnDoc = connectionRepository.findByClientId(clientId)
                val lastSyncedAt = latestConnDoc?.lastSyncedAt
                val baseJqlSuffix =
                    if (lastSyncedAt == null) "AND updated >= -30d ORDER BY updated DESC" else "ORDER BY updated DESC"

                projectsToIndex.forEach { projectKey ->
                    val key = projectKey.value
                    val jql = "project = $key $baseJqlSuffix"
                    val jervisProjectId = jiraProjectMapping[key] // null if not mapped

                    runCatching {
                        val mapped = jervisProjectId?.toHexString() ?: "(not mapped)"
                        indexingRegistry.info("jira", "Indexing project=$key mappedProject=$mapped")
                    }

                    try {
                        val tenantHost = connValid.tenant.value
                        api
                            .searchIssues(connValid, jql, updatedSinceEpochMs = lastSyncedAt?.toEpochMilli())
                            .collect { issue ->
                                val assignedToMe = me?.let { issue.assignee?.value == it.value } ?: false
                                if (!assignedToMe) {
                                    // Shallow: one compact narrative chunk
                                    indexIssueSummaryShallow(
                                        clientId,
                                        issue.key,
                                        projectKey,
                                        assignedToMe,
                                        issue.summary,
                                        issue.status,
                                        issue.updated,
                                        tenantHost,
                                        jervisProjectId,
                                    )
                                    runCatching {
                                        indexingRegistry.progress(
                                            "jira",
                                            processedInc = 1,
                                            message = "Processed issue ${issue.key} (shallow)",
                                        )
                                    }
                                } else {
                                    // Deep: summary + comments + attachments
                                    indexIssueSummaryShallow(
                                        clientId,
                                        issue.key,
                                        projectKey,
                                        assignedToMe,
                                        issue.summary,
                                        issue.status,
                                        issue.updated,
                                        tenantHost,
                                        jervisProjectId,
                                    )
                                    indexIssueCommentsDeep(
                                        clientId = clientId,
                                        issueKey = issue.key,
                                        project = projectKey,
                                        tenantHost = tenantHost,
                                        jervisProjectId = jervisProjectId,
                                    )
                                    // Index attachments (screenshots, docs, logs, etc.)
                                    jiraAttachmentIndexer.indexIssueAttachments(
                                        conn = connValid,
                                        issueKey = issue.key,
                                        clientId = clientId,
                                        tenantHost = tenantHost,
                                        projectId = jervisProjectId,
                                    )
                                    runCatching {
                                        indexingRegistry.progress(
                                            "jira",
                                            processedInc = 1,
                                            message = "Processed issue ${issue.key} (deep)",
                                        )
                                    }
                                }
                            }
                    } catch (e: Exception) {
                        logger.error(e) { "JIRA_INDEX: search failed for client=${clientId.toHexString()} project=$key" }
                        // If this looks like an auth error, mark INVALID to stop further attempts until user fixes it
                        if (isAuthError(e)) {
                            val doc = connectionRepository.findByClientId(clientId)
                            if (doc != null) {
                                runCatching { connectionService.markAuthInvalid(doc, e.message) }
                            }
                        }
                        // Persist error for visibility regardless of type (e.g., HTTP 410)
                        runCatching { errorLogService.recordError(e, clientId = clientId, projectId = jervisProjectId) }
                        // Fail fast: continue other keys but surface error count via logs
                        runCatching { indexingRegistry.error("jira", "Search failed for project=$key: ${e.message}") }
                    }
                }

                logger.info { "JIRA_INDEX: Done for client=${clientId.toHexString()} (projects=${allJiraProjects.size})" }

                // Update last synced timestamp on successful completion
                try {
                    val doc = connectionRepository.findByClientId(clientId)
                    if (doc != null) {
                        val updated = doc.copy(lastSyncedAt = Instant.now(), updatedAt = Instant.now())
                        connectionRepository.save(updated)
                        indexingRegistry.info("jira", "Updated lastSyncedAt for client=${clientId.toHexString()}")
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "JIRA_INDEX: Failed to update lastSyncedAt for client=${clientId.toHexString()}" }
                    runCatching { indexingRegistry.error("jira", "Failed to update lastSyncedAt: ${e.message}") }
                    runCatching { errorLogService.recordError(e, clientId = clientId) }
                }
            } finally {
                kotlin.runCatching {
                    indexingRegistry.finish(
                        "jira",
                        message = "Jira indexer finished for client=${clientId.toHexString()}",
                    )
                }
            }
        }

    private suspend fun indexIssueSummaryShallow(
        clientId: ObjectId,
        issueKey: String,
        project: JiraProjectKey,
        assignedToMe: Boolean,
        summary: String,
        status: String,
        updated: Instant,
        tenantHost: String,
        jervisProjectId: ObjectId?,
    ) {
        val text =
            buildString {
                appendLine("Issue: $issueKey  Project: ${project.value}")
                appendLine("Status: $status  AssignedToMe: $assignedToMe")
                appendLine("Goal: $summary")
            }.trim()

        val chunks = textChunkingService.splitText(text)
        if (chunks.isEmpty()) {
            logger.debug { "JIRA_INDEX: No content to index for issue $issueKey" }
            return
        }

        var stored = 0
        chunks.forEachIndexed { index, chunk ->
            val rag =
                RagDocument(
                    projectId = jervisProjectId, // Map to Jervis project if exists
                    clientId = clientId,
                    text = chunk.text(),
                    ragSourceType = RagSourceType.JIRA,
                    subject = "Jira issue $issueKey",
                    timestamp = updated.toString(),
                    parentRef = issueKey,
                    branch = "main",
                    sourceUri = "https://$tenantHost/browse/$issueKey",
                    chunkId = index,
                    chunkOf = chunks.size,
                )
            ragIndexingService.indexDocument(rag, com.jervis.domain.model.ModelTypeEnum.EMBEDDING_TEXT)
            stored++
        }

        val projectInfo = if (jervisProjectId != null) " projectId=${jervisProjectId.toHexString()}" else ""
        logger.info { "JIRA_INDEX: Stored shallow summary for $issueKey chunks=$stored$projectInfo" }
    }

    private suspend fun indexIssueCommentsDeep(
        clientId: ObjectId,
        issueKey: String,
        project: JiraProjectKey,
        tenantHost: String,
        jervisProjectId: ObjectId?,
    ) {
        val conn = selection.getConnection(clientId).let { auth.ensureValidToken(it) }
        val indexDoc = issueIndexRepository.findByClientIdAndIssueKey(clientId, issueKey)
        var lastProcessedId = indexDoc?.lastEmbeddedCommentId
        var process = lastProcessedId == null
        var processedCount = 0

        api.fetchIssueComments(conn, issueKey).collect { pair ->
            val commentId = pair.first
            val body = pair.second // Already plain text (HTML was stripped in API client)

            if (!process) {
                if (commentId == lastProcessedId) {
                    process = true
                }
                return@collect
            }

            if (body.isBlank()) return@collect

            // Note: body is already plain text, but we need HTML to extract links properly
            // The links are already stripped by stripHtml in StubJiraApiClient
            // We'll extract URLs from the plain text as a workaround
            val links = extractLinksFromText(body)

            val text =
                buildString {
                    appendLine("Issue: $issueKey  Project: ${project.value}")
                    appendLine("CommentId: $commentId")
                    appendLine("Comment: $body")

                    // Add links section for better searchability (similar to email indexing)
                    if (links.isNotEmpty()) {
                        appendLine()
                        appendLine("--- Links in this comment ---")
                        links.forEach { url ->
                            appendLine("Link: $url")
                        }
                    }
                }.trim()

            val chunks = textChunkingService.splitText(text)
            if (chunks.isEmpty()) return@collect

            chunks.forEachIndexed { index, chunk ->
                val rag =
                    RagDocument(
                        projectId = jervisProjectId,
                        clientId = clientId,
                        text = chunk.text(),
                        ragSourceType = RagSourceType.JIRA,
                        subject = "Jira comment on $issueKey",
                        timestamp = Instant.now().toString(),
                        parentRef = issueKey,
                        branch = "main",
                        sourceUri = "https://$tenantHost/browse/$issueKey?focusedCommentId=$commentId&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-$commentId",
                        chunkId = index,
                        chunkOf = chunks.size,
                    )
                ragIndexingService.indexDocument(rag, com.jervis.domain.model.ModelTypeEnum.EMBEDDING_TEXT)
            }

            // Index links separately
            if (links.isNotEmpty()) {
                links.forEach { url ->
                    runCatching {
                        linkIndexingService.indexUrl(
                            url = url,
                            projectId = jervisProjectId,
                            clientId = clientId,
                            sourceType = RagSourceType.JIRA_LINK_CONTENT,
                            parentRef = issueKey,
                        )
                    }.onFailure { e ->
                        logger.warn { "Failed to index link $url from Jira comment $commentId: ${e.message}" }
                    }
                }
            }

            lastProcessedId = commentId
            processedCount++
        }

        if (processedCount > 0) {
            val newDoc =
                if (indexDoc == null) {
                    com.jervis.entity.jira.JiraIssueIndexDocument(
                        clientId = clientId,
                        issueKey = issueKey,
                        projectKey = project.value,
                        lastSeenUpdated = Instant.now(),
                        lastEmbeddedCommentId = lastProcessedId,
                        etag = null,
                        archived = false,
                        updatedAt = Instant.now(),
                    )
                } else {
                    indexDoc.copy(
                        lastSeenUpdated = Instant.now(),
                        lastEmbeddedCommentId = lastProcessedId,
                        updatedAt = Instant.now(),
                    )
                }
            issueIndexRepository.save(newDoc)
            logger.info { "JIRA_INDEX: Stored $processedCount new comments for $issueKey" }
        }
    }

    /**
     * Extract URLs from plain text.
     * Used for Jira comments where HTML has already been stripped.
     */
    private fun extractLinksFromText(text: String): List<String> {
        val urlPattern = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
        return urlPattern
            .findAll(text)
            .map { it.value }
            .distinct()
            .toList()
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
}
