package com.jervis.service.jira

import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.service.background.PendingTaskService
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
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - CentralPoller fetches FULL data from API → stores in MongoDB as NEW
 * - This indexer reads NEW documents from MongoDB (NO API CALLS)
 * - Creates DATA_PROCESSING PendingTask instead of auto-indexing
 * - Task goes to KoogQualifierAgent (CPU) for structuring:
 *   - Chunks large issues with overlap (description + comments)
 *   - Creates Graph nodes (issue metadata, assignee, status, links)
 *   - Creates RAG chunks (searchable content)
 *   - Links Graph ↔ RAG bi-directionally
 * - After qualification: task marked DONE or READY_FOR_GPU
 *
 * Pure ETL: MongoDB → PendingTask → Qualifier → Graph + RAG
 */
@Service
@Order(10) // Start after WeaviateSchemaInitializer
class JiraContinuousIndexer(
    private val stateManager: JiraStateManager,
    private val pendingTaskService: PendingTaskService,
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
        logger.debug { "Processing Jira issue ${doc.issueKey} (${doc.summary})" }

        // Mark as INDEXING to prevent concurrent processing
        stateManager.markAsIndexing(doc)

        try {
            // Build Jira issue content for task
            val issueContent = buildString {
                append("# ${doc.issueKey}: ${doc.summary}\n\n")
                append("**Type:** ${doc.issueType}\n")
                append("**Status:** ${doc.status}\n")
                append("**Priority:** ${doc.priority}\n")
                if (!doc.assignee.isNullOrBlank()) {
                    append("**Assignee:** ${doc.assignee}\n")
                }
                if (!doc.reporter.isNullOrBlank()) {
                    append("**Reporter:** ${doc.reporter}\n")
                }
                append("**Created:** ${doc.created}\n")
                append("**Updated:** ${doc.updated}\n")
                append("\n")

                if (!doc.description.isNullOrBlank()) {
                    append("## Description\n\n")
                    append(doc.description)
                    append("\n\n")
                }

                if (doc.labels.isNotEmpty()) {
                    append("**Labels:** ${doc.labels.joinToString(", ")}\n\n")
                }

                // Add comments
                if (doc.comments.isNotEmpty()) {
                    append("## Comments\n\n")
                    doc.comments.forEachIndexed { index, comment ->
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
                append("- **Connection ID:** ${doc.connectionId.toHexString()}\n")
                append("- **Issue ID:** ${doc.issueId}\n")
                append("- **Comment Count:** ${doc.comments.size}\n")
                append("- **Attachment Count:** ${doc.attachments.size}\n")
            }

            // Create DATA_PROCESSING task instead of auto-indexing
            pendingTaskService.createTask(
                taskType = PendingTaskTypeEnum.DATA_PROCESSING,
                content = issueContent,
                projectId = null,
                clientId = doc.clientId,
                correlationId = "jira:${doc.issueKey}",
            )

            // Mark as task created
            stateManager.markAsIndexed(
                issue = doc,
                summaryChunks = 0,
                commentChunks = 0,
                commentCount = doc.comments.size,
                attachmentCount = doc.attachments.size,
            )

            // Report progress
            kotlin.runCatching {
                indexingRegistry.ensureTool(com.jervis.service.indexing.status.IndexingStatusRegistry.ToolStateEnum.JIRA, displayName = "Atlassian (Jira)")
                indexingRegistry.progress(
                    com.jervis.service.indexing.status.IndexingStatusRegistry.ToolStateEnum.JIRA,
                    processedInc = 1,
                    message = "Created task for ${doc.issueKey}",
                )
            }

            logger.info { "Created DATA_PROCESSING task for Jira issue: ${doc.issueKey}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create task for Jira issue ${doc.issueKey}" }
            stateManager.markAsFailed(doc, "Task creation failed: ${e.message}")
        }
    }
}
