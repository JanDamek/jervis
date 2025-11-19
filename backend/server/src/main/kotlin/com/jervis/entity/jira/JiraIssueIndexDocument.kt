package com.jervis.entity.jira

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Lightweight Jira issue index state for reconciliation and idempotency.
 * Tracks what has been indexed to enable incremental updates.
 */
@Document(collection = "jira_issue_index")
@CompoundIndexes(
    CompoundIndex(name = "client_issue_unique", def = "{'clientId': 1, 'issueKey': 1}", unique = true),
)
data class JiraIssueIndexDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val clientId: ObjectId,
    @Indexed
    val issueKey: String,
    val projectKey: String,
    val lastSeenUpdated: Instant? = null,
    val lastEmbeddedCommentId: String? = null,
    val etag: String? = null,
    val archived: Boolean = false,
    @Indexed
    val updatedAt: Instant = Instant.now(),
    /** Indexing state: NEW, INDEXING, INDEXED, FAILED (similar to EmailMessageDocument) */
    @Indexed
    val state: String = "NEW",
    /** Error message if state=FAILED */
    val errorMessage: String? = null,

    // Incremental indexing: track what has been indexed
    /** Hash of summary + description to detect changes */
    val contentHash: String? = null,
    /** Hash of status field to detect status changes */
    val statusHash: String? = null,
    /** List of indexed attachment IDs to detect new attachments */
    val indexedAttachmentIds: List<String> = emptyList(),
    /** Timestamp when issue was last fully indexed (shallow or deep) */
    val lastIndexedAt: Instant? = null,

    // UI status tracking - updated after each indexing run
    /** Number of RAG chunks for issue summary */
    val summaryChunkCount: Int = 0,
    /** Number of RAG chunks for comments */
    val commentChunkCount: Int = 0,
    /** Number of comments indexed */
    val commentCount: Int = 0,
    /** Number of attachments successfully indexed */
    val attachmentCount: Int = 0,
    /** Total RAG chunks (summary + comments + attachments) */
    val totalRagChunks: Int = 0,
    /** Current Jira status (e.g., "In Progress", "Done") */
    val currentStatus: String? = null,
    /** Current assignee account ID */
    val currentAssignee: String? = null,
    /** Issue summary/title for quick display */
    val issueSummary: String? = null,
    /** Last indexing error message (null if successful) */
    val lastIndexingError: String? = null,
)
