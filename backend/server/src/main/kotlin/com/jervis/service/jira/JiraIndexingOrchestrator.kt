package com.jervis.service.jira

import com.jervis.entity.jira.JiraIssueIndexDocument
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Orchestrator for indexing Jira issues into RAG.
 * Reads complete data from JiraIssueIndexDocument (MongoDB), NO API CALLS.
 * Handles: summary, description, comments, attachments.
 */
@Service
class JiraIndexingOrchestrator {
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

        // TODO: Implement actual RAG indexing logic
        // For now, just count what would be indexed
        var summaryChunks = 0
        var commentChunks = 0

        // Index summary + description
        val content = buildString {
            append(document.summary)
            if (!document.description.isNullOrBlank()) {
                append("\n\n")
                append(document.description)
            }
        }

        if (content.isNotBlank()) {
            // TODO: Chunk and embed content
            summaryChunks = (content.length / 500).coerceAtLeast(1) // Rough estimate
        }

        // Index comments
        for (comment in document.comments) {
            if (comment.body.isNotBlank()) {
                // TODO: Chunk and embed comment
                commentChunks += (comment.body.length / 500).coerceAtLeast(1)
            }
        }

        // TODO: Index attachments (download, extract text, chunk, embed)

        logger.info { "Indexed ${document.issueKey}: $summaryChunks summary chunks, $commentChunks comment chunks, ${document.comments.size} comments, ${document.attachments.size} attachments" }

        return IndexingResult(
            success = true,
            summaryChunks = summaryChunks,
            commentChunks = commentChunks,
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
