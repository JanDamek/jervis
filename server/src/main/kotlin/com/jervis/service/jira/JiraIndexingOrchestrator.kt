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

            // Shallow discovery window (last 30 days)
            val baseJqlSuffix = "AND updated >= -30d ORDER BY updated DESC"

            effectiveProjectKeys.forEach { key ->
                val jql = "project = $key $baseJqlSuffix"

                runCatching {
                    val connValid = selection.getConnection(clientId).let { auth.ensureValidToken(it) }
                    val tenantHost = connValid.tenant.value
                    api.searchIssues(connValid, jql).collect { issue ->
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
                            // Deep placeholder: summary only for now, comments later
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
                        }
                    }
                }.onFailure { e ->
                    logger.error(e) { "JIRA_INDEX: search failed for client=${clientId.toHexString()} project=$key" }
                    // Fail fast: continue other keys but surface error count via logs
                }
            }

            logger.info { "JIRA_INDEX: Done for client=${clientId.toHexString()} (projects=${effectiveProjectKeys.size})" }
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

        "jira-issue-summary:$issueKey"

        // For now, we cannot scope to projectId in RagDocument (no DTO/controller), so use client scope only
        // VectorStoreIndexService requires projectId for trackIndexed; we keep mono-repo pattern separate.
        // Use a "standalone" style by skipping tracking here if project context is unknown.
        val embedding = embeddingGateway.callEmbedding(com.jervis.domain.model.ModelTypeEnum.EMBEDDING_TEXT, text)

        val rag =
            RagDocument(
                projectId = null, // unknown here; Jira is client-level context
                clientId = clientId,
                summary = text,
                ragSourceType = RagSourceType.JIRA,
                subject = "Jira issue $issueKey",
                timestamp = updated.toString(),
                parentRef = issueKey,
                branch = "main",
                contentType = "jira-issue-summary",
                symbolName = "jira-issue:$issueKey",
                sourceUri = "https://$tenantHost/browse/$issueKey",
            )
        val vectorId = vectorStorage.store(com.jervis.domain.model.ModelTypeEnum.EMBEDDING_TEXT, rag, embedding)

        // We cannot use trackIndexed() which requires projectId. If a client-level tracking exists later, switch to it.
        logger.info { "JIRA_INDEX: Stored shallow summary for $issueKey vectorId=$vectorId" }
    }
}
