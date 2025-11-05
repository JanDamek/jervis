package com.jervis.service.jira

import com.jervis.domain.jira.JiraProjectKey
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.rag.VectorStoreIndexService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class JiraIndexingOrchestrator(
    private val selection: JiraSelectionService,
    private val api: JiraApiClient,
    private val auth: JiraAuthService,
    private val emailSignal: JiraEmailSignalService,
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val vectorStoreIndexService: VectorStoreIndexService,
    private val projectRepository: com.jervis.repository.mongo.ProjectMongoRepository,
    private val clientRepository: com.jervis.repository.mongo.ClientMongoRepository,
    private val connectionRepository: com.jervis.repository.mongo.JiraConnectionMongoRepository,
    private val issueIndexRepository: com.jervis.repository.mongo.JiraIssueIndexMongoRepository,
    private val textChunkingService: com.jervis.service.text.TextChunkingService,
) {
    private val logger = KotlinLogging.logger {}

    /** Entry point for Jira indexing for a client. Safe to call from scheduler. */
    suspend fun indexClient(clientId: ObjectId) =
        withContext(Dispatchers.IO) {
            logger.info { "JIRA_INDEX: Start for client=${clientId.toHexString()}" }

            selection.getConnection(clientId).let { auth.ensureValidToken(it) }

            // Ensure selections exist (fail fast if missing; no background config tasks)
            val (primaryProject, me) =
                runCatching { selection.ensureSelectionsOrCreateTasks(clientId) }
                    .onFailure { e ->
                        logger.warn { "JIRA_INDEX: Missing selections for client=${clientId.toHexString()} - ${e.message}" }
                    }.getOrElse { return@withContext }

            // Resolve effective Jira project keys across all projects for this client (override per project or fall back to client's primary project)
            val effectiveProjectKeys: Set<String> =
                try {
                    val projects = projectRepository.findAll().toList().filter { it.clientId == clientId }
                    val keys = projects.mapNotNull { it.overrides?.jiraProjectKey ?: primaryProject.value }
                    keys.toSet()
                } catch (e: Exception) {
                    logger.warn(
                        e,
                    ) {
                        "JIRA_INDEX: Failed to enumerate projects for client=${clientId.toHexString()}, falling back to primary project only"
                    }
                    setOf(primaryProject.value)
                }

            // Determine incremental window based on last sync
            val connDoc = connectionRepository.findByClientId(clientId)
            val lastSyncedAt = connDoc?.lastSyncedAt
            val baseJqlSuffix =
                if (lastSyncedAt == null) "AND updated >= -30d ORDER BY updated DESC" else "ORDER BY updated DESC"

            effectiveProjectKeys.forEach { key ->
                val jql = "project = $key $baseJqlSuffix"

                runCatching {
                    val connValid = selection.getConnection(clientId).let { auth.ensureValidToken(it) }
                    val tenantHost = connValid.tenant.value
                    api
                        .searchIssues(connValid, jql, updatedSinceEpochMs = lastSyncedAt?.toEpochMilli())
                        .collect { issue ->
                            val assignedToMe = issue.assignee?.value == me.value
                            val projectKey = JiraProjectKey(key)
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
                                )
                            } else {
                                // Deep: summary + comments
                                indexIssueSummaryShallow(
                                    clientId,
                                    issue.key,
                                    projectKey,
                                    assignedToMe,
                                    issue.summary,
                                    issue.status,
                                    issue.updated,
                                    tenantHost,
                                )
                                indexIssueCommentsDeep(
                                    clientId = clientId,
                                    issueKey = issue.key,
                                    project = projectKey,
                                    tenantHost = tenantHost,
                                )
                            }
                        }
                }.onFailure { e ->
                    logger.error(e) { "JIRA_INDEX: search failed for client=${clientId.toHexString()} project=$key" }
                    // Fail fast: continue other keys but surface error count via logs
                }
            }

            logger.info { "JIRA_INDEX: Done for client=${clientId.toHexString()} (projects=${effectiveProjectKeys.size})" }

            // Update last synced timestamp on successful completion
            runCatching {
                val doc = connectionRepository.findByClientId(clientId)
                if (doc != null) {
                    val updated = doc.copy(lastSyncedAt = Instant.now(), updatedAt = Instant.now())
                    connectionRepository.save(updated)
                }
            }.onFailure { e ->
                logger.warn(e) { "JIRA_INDEX: Failed to update lastSyncedAt for client=${clientId.toHexString()}" }
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
            val embedding =
                embeddingGateway.callEmbedding(com.jervis.domain.model.ModelTypeEnum.EMBEDDING_TEXT, chunk.text())

            val rag =
                RagDocument(
                    projectId = null, // unknown here; Jira is client-level context
                    clientId = clientId,
                    summary = chunk.text(),
                    ragSourceType = RagSourceType.JIRA,
                    subject = "Jira issue $issueKey",
                    timestamp = updated.toString(),
                    parentRef = issueKey,
                    branch = "main",
                    contentType = "jira-issue-summary",
                    symbolName = "jira-issue:$issueKey",
                    sourceUri = "https://$tenantHost/browse/$issueKey",
                    chunkId = index,
                    chunkOf = chunks.size,
                )
            vectorStorage.store(com.jervis.domain.model.ModelTypeEnum.EMBEDDING_TEXT, rag, embedding)
            stored++
        }

        // We cannot use trackIndexed() which requires projectId. If a client-level tracking exists later, switch to it.
        logger.info { "JIRA_INDEX: Stored shallow summary for $issueKey chunks=$stored" }
    }

    private suspend fun indexIssueCommentsDeep(
        clientId: ObjectId,
        issueKey: String,
        project: JiraProjectKey,
        tenantHost: String,
    ) {
        val conn = selection.getConnection(clientId).let { auth.ensureValidToken(it) }
        val indexDoc = issueIndexRepository.findByClientIdAndIssueKey(clientId, issueKey)
        var lastProcessedId = indexDoc?.lastEmbeddedCommentId
        var process = lastProcessedId == null
        var processedCount = 0

        api.fetchIssueComments(conn, issueKey).collect { pair ->
            val commentId = pair.first
            val body = pair.second

            if (!process) {
                if (commentId == lastProcessedId) {
                    process = true
                }
                return@collect
            }

            if (body.isBlank()) return@collect

            val text =
                buildString {
                    appendLine("Issue: $issueKey  Project: ${project.value}")
                    appendLine("CommentId: $commentId")
                    appendLine("Comment: $body")
                }.trim()

            val chunks = textChunkingService.splitText(text)
            if (chunks.isEmpty()) return@collect

            chunks.forEachIndexed { index, chunk ->
                val embedding =
                    embeddingGateway.callEmbedding(com.jervis.domain.model.ModelTypeEnum.EMBEDDING_TEXT, chunk.text())
                val rag =
                    RagDocument(
                        projectId = null,
                        clientId = clientId,
                        summary = chunk.text(),
                        ragSourceType = RagSourceType.JIRA,
                        subject = "Jira comment on $issueKey",
                        timestamp = Instant.now().toString(),
                        parentRef = issueKey,
                        branch = "main",
                        contentType = "jira-issue-comment",
                        symbolName = "jira-issue-comment:$issueKey:$commentId",
                        sourceUri = "https://$tenantHost/browse/$issueKey?focusedCommentId=$commentId&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-$commentId",
                        chunkId = index,
                        chunkOf = chunks.size,
                    )
                vectorStorage.store(com.jervis.domain.model.ModelTypeEnum.EMBEDDING_TEXT, rag, embedding)
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
}
