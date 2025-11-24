package com.jervis.service.jira

import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.rag.DocumentToStore
import com.jervis.rag.KnowledgeService
import com.jervis.rag.KnowledgeType
import com.jervis.rag.StoreRequest
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Orchestrator for indexing Jira issues into RAG.
 * Reads complete data from JiraIssueIndexDocument (MongoDB), NO API CALLS.
 * Handles: summary, description, comments, attachments.
 */
@Service
class JiraIndexingOrchestrator(
    private val knowledgeService: KnowledgeService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Index a single Jira issue from MongoDB document.
     * All data is already in the document (fetched by CentralPoller).
     * Returns result with chunk counts.
     */
    suspend fun indexSingleIssue(
        clientId: ObjectId,
        document: JiraIssueIndexDocument,
    ): IndexingResult {
        logger.debug { "Indexing issue ${document.issueKey}: ${document.summary}" }

        val documentsToStore = mutableListOf<DocumentToStore>()

        // 1. Index summary + description as main document
        val mainContent = buildString {
            append("# ${document.issueKey}: ${document.summary}\n\n")
            append("**Type:** ${document.issueType}\n")
            append("**Status:** ${document.status}\n")
            append("**Priority:** ${document.priority}\n")
            if (!document.assignee.isNullOrBlank()) {
                append("**Assignee:** ${document.assignee}\n")
            }
            if (!document.reporter.isNullOrBlank()) {
                append("**Reporter:** ${document.reporter}\n")
            }
            append("\n")

            if (!document.description.isNullOrBlank()) {
                append("## Description\n\n")
                append(document.description)
                append("\n\n")
            }

            if (document.labels.isNotEmpty()) {
                append("**Labels:** ${document.labels.joinToString(", ")}\n")
            }
        }

        if (mainContent.isNotBlank()) {
            documentsToStore.add(
                DocumentToStore(
                    documentId = "jira:${document.issueKey}",
                    content = mainContent,
                    clientId = clientId,
                    type = KnowledgeType.DOCUMENT,
                    title = "${document.issueKey}: ${document.summary}",
                    location = "Jira Issue ${document.issueKey}",
                    projectId = null, // TODO: Add project mapping if available
                )
            )
        }

        // 2. Index each comment as separate document
        document.comments.forEachIndexed { index, comment ->
            if (comment.body.isNotBlank()) {
                val commentContent = buildString {
                    append("# Comment on ${document.issueKey}\n\n")
                    append("**Author:** ${comment.author}\n")
                    append("**Created:** ${comment.created}\n\n")
                    append(comment.body)
                }

                documentsToStore.add(
                    DocumentToStore(
                        documentId = "jira:${document.issueKey}:comment:$index",
                        content = commentContent,
                        clientId = clientId,
                        type = KnowledgeType.DOCUMENT,
                        title = "Comment by ${comment.author} on ${document.issueKey}",
                        location = "Jira Issue ${document.issueKey} - Comment",
                        relatedDocs = listOf("jira:${document.issueKey}"),
                        projectId = null,
                    )
                )
            }
        }

        // 3. Store all documents in RAG
        val result = if (documentsToStore.isNotEmpty()) {
            try {
                knowledgeService.store(StoreRequest(documents = documentsToStore))
            } catch (e: Exception) {
                logger.error(e) { "Failed to store Jira issue ${document.issueKey} in RAG" }
                return IndexingResult(
                    success = false,
                    summaryChunks = 0,
                    commentChunks = 0,
                    commentCount = document.comments.size,
                    attachmentCount = document.attachments.size,
                )
            }
        } else {
            null
        }

        val mainDoc = result?.documents?.firstOrNull { it.documentId == "jira:${document.issueKey}" }
        val commentDocs = result?.documents?.filter { it.documentId.startsWith("jira:${document.issueKey}:comment:") } ?: emptyList()

        logger.info {
            "Indexed ${document.issueKey}: ${mainDoc?.totalChunks ?: 0} summary chunks, " +
            "${commentDocs.sumOf { it.totalChunks }} comment chunks across ${document.comments.size} comments, " +
            "${document.attachments.size} attachments (not yet indexed)"
        }

        return IndexingResult(
            success = true,
            summaryChunks = mainDoc?.totalChunks ?: 0,
            commentChunks = commentDocs.sumOf { it.totalChunks },
            commentCount = document.comments.size,
            attachmentCount = document.attachments.size,
        )
    }

    data class IndexingResult(
        val success: Boolean,
        val summaryChunks: Int = 0,
        val commentChunks: Int = 0,
        val commentCount: Int = 0,
        val attachmentCount: Int = 0,
    )
}
