package com.jervis.service.jira

import com.jervis.domain.jira.JiraIssue
import com.jervis.entity.atlassian.AtlassianConnectionDocument
import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.repository.AtlassianConnectionMongoRepository
import com.jervis.repository.JiraIssueIndexMongoRepository
import com.jervis.service.atlassian.AtlassianApiClient
import com.jervis.service.atlassian.AtlassianAuthService
import com.jervis.service.indexing.AbstractContinuousIndexer
import com.jervis.service.indexing.status.IndexingStatusRegistry
import com.jervis.service.jira.state.JiraStateManager
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for Jira issues.
 * Polls DB for NEW issues, fetches full content from API, indexes to RAG.
 *
 * Pattern:
 * 1. JiraContinuousPoller discovers issues via API search → saves to DB as NEW
 * 2. This indexer picks up NEW issues → fetches details → indexes → marks INDEXED
 *
 * This separates discovery (fast, bulk) from indexing (slow, detailed).
 */
@Service
@Order(10) // Start after WeaviateSchemaInitializer
class JiraContinuousIndexer(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val api: AtlassianApiClient,
    private val auth: AtlassianAuthService,
    private val stateManager: JiraStateManager,
    private val orchestrator: JiraIndexingOrchestrator,
    private val flowProps: com.jervis.configuration.properties.IndexingFlowProperties,
    private val indexingRegistry: IndexingStatusRegistry,
    private val repository: JiraIssueIndexMongoRepository,
) : AbstractContinuousIndexer<AtlassianConnectionDocument, JiraIssueIndexDocument>() {
    override val indexerName: String = "JiraContinuousIndexer"
    override val bufferSize: Int get() = flowProps.bufferSize

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting $indexerName (single instance for all accounts)..." }
        scope.launch {
            kotlin.runCatching {
                indexingRegistry.start(
                    "jira",
                    displayName = "Atlassian (Jira)",
                    message = "Starting continuous Jira indexing for all accounts",
                )
            }
            runCatching { startContinuousIndexing() }
                .onFailure { e -> logger.error(e) { "Continuous indexer crashed" } }
                .also {
                    kotlin.runCatching {
                        indexingRegistry.finish(
                            "jira",
                            message = "Jira indexer stopped",
                        )
                    }
                }
        }
    }

    override fun newItemsFlow(): Flow<JiraIssueIndexDocument> =
        stateManager.continuousNewIssuesAllAccounts()

    override suspend fun getAccountForItem(item: JiraIssueIndexDocument): AtlassianConnectionDocument? =
        connectionRepository.findById(item.accountId)

    override fun itemLogLabel(item: JiraIssueIndexDocument) = "Issue:${item.issueKey} project:${item.projectKey}"

    override suspend fun fetchContentIO(
        account: AtlassianConnectionDocument,
        item: JiraIssueIndexDocument,
    ): JiraIssue? {
        // Mark as INDEXING to prevent concurrent processing
        stateManager.markAsIndexing(item)

        // Fetch full issue from API using searchIssues with specific key
        val conn = auth.ensureValidToken(account.toDomain())
        return try {
            val jql = "key = ${item.issueKey}"
            api.searchIssues(conn, jql).firstOrNull()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch Jira issue ${item.issueKey}: ${e.message}" }
            null
        }
    }

    override suspend fun processAndIndex(
        account: AtlassianConnectionDocument,
        item: JiraIssueIndexDocument,
        content: Any,
    ): IndexingResult {
        val issue = content as JiraIssue

        // Use existing orchestrator logic to index issue
        // This indexes: summary, comments, attachments, links
        val result =
            orchestrator.indexSingleIssue(
                clientId = item.clientId,
                issue = issue,
                tenantHost = account.tenant,
                jervisProjectId = null, // Jira issues don't have Jervis project association at index level
            )

        // Store result for markAsIndexed
        latestResult.set(result)

        // Report progress to indexing registry
        kotlin.runCatching {
            val toolKey = "jira"
            indexingRegistry.ensureTool(toolKey, displayName = "Atlassian (Jira)")
            if (result.success) {
                indexingRegistry.progress(
                    toolKey,
                    processedInc = 1,
                    message = "Indexed issue ${issue.key}",
                )
            }
        }

        return IndexingResult(
            success = result.success,
            plainText = "${issue.summary} ${issue.description ?: ""}",
            ragDocumentId = null,
        )
    }

    override suspend fun markAsIndexed(
        account: AtlassianConnectionDocument,
        item: JiraIssueIndexDocument,
    ) {
        val result = latestResult.get()

        stateManager.markAsIndexed(
            issue = item,
            summaryChunks = result?.summaryChunks ?: 0,
            commentChunks = result?.commentChunks ?: 0,
            commentCount = result?.commentCount ?: 0,
            attachmentCount = result?.attachmentCount ?: 0,
        )
    }

    override suspend fun markAsFailed(
        account: AtlassianConnectionDocument?,
        item: JiraIssueIndexDocument,
        reason: String,
    ) {
        stateManager.markAsFailed(item, reason)
    }

    companion object {
        private val latestResult =
            java.util.concurrent.atomic
                .AtomicReference<JiraIndexingOrchestrator.IndexSingleIssueResult?>()
    }
}
