package com.jervis.service.jira

import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.service.indexing.status.IndexingStatusRegistry
import com.jervis.service.jira.state.JiraStateManager
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
 * Architecture:
 * - CentralPoller fetches FULL data from API → stores in MongoDB as NEW
 * - This indexer reads NEW documents from MongoDB (NO API CALLS)
 * - Chunks text, creates embeddings, stores to Weaviate
 * - Marks as INDEXED
 *
 * Pure ETL: MongoDB → Weaviate
 */
@Service
@Order(10) // Start after WeaviateSchemaInitializer
class JiraContinuousIndexer(
    private val stateManager: JiraStateManager,
    private val orchestrator: JiraIndexingOrchestrator,
    private val indexingRegistry: IndexingStatusRegistry,
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
        logger.debug { "Indexing Jira issue ${doc.issueKey} (${doc.summary})" }

        // Mark as INDEXING to prevent concurrent processing
        stateManager.markAsIndexing(doc)

        // Index to RAG (uses data already in MongoDB, NO API CALLS)
        val result = orchestrator.indexSingleIssue(
            clientId = doc.clientId,
            document = doc, // Pass full document with all data
        )

        if (result.success) {
            // Mark as indexed with stats
            stateManager.markAsIndexed(
                issue = doc,
                summaryChunks = result.summaryChunks,
                commentChunks = result.commentChunks,
                commentCount = result.commentCount,
                attachmentCount = result.attachmentCount,
            )

            // Report progress
            kotlin.runCatching {
                indexingRegistry.ensureTool(com.jervis.service.indexing.status.IndexingStatusRegistry.ToolStateEnum.JIRA, displayName = "Atlassian (Jira)")
                indexingRegistry.progress(
                    com.jervis.service.indexing.status.IndexingStatusRegistry.ToolStateEnum.JIRA,
                    processedInc = 1,
                    message = "Indexed issue ${doc.issueKey}",
                )
            }

            logger.info { "Indexed Jira issue ${doc.issueKey}: ${result.summaryChunks + result.commentChunks} chunks" }
        } else {
            stateManager.markAsFailed(doc, "Indexing failed")
            logger.warn { "Failed to index Jira issue ${doc.issueKey}" }
        }
    }
}
