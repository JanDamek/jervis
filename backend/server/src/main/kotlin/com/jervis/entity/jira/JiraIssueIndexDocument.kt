package com.jervis.entity.jira

import com.jervis.types.ClientId
import com.jervis.types.ConnectionId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Jira issue index - tracking which issues have been processed.
 *
 * STATE MACHINE: NEW -> INDEXED (or FAILED)
 *
 * STATES:
 * - NEW: Full issue data from Jira API, ready for indexing
 * - INDEXED: Minimal tracking record (data in RAG/Graph via sourceUrn)
 * - FAILED: NEW state with error - full data kept for retry
 *
 * FLOW:
 * 1. CentralPoller fetches → saves as NEW with full data
 * 2. JiraContinuousIndexer creates PendingTask → converts to INDEXED (minimal)
 * 3. KoogQualifierAgent stores to RAG/Graph with sourceUrn
 * 4. Future lookups use sourceUrn to find original in Jira
 *
 * MONGODB STORAGE (single instance, no INDEXING state needed):
 * - NEW/FAILED: Full document with description, comments, attachments
 * - INDEXED: Minimal document - only id, connectionId, issueKey, state, jiraUpdatedAt
 *
 * BREAKING CHANGE - MIGRATION REQUIRED:
 * Sealed class structure requires _class discriminator field in MongoDB.
 * Old documents without _class will FAIL deserialization (fail-fast design).
 *
 * MIGRATION: Drop collection before starting server:
 *   db.jira_issues.drop()
 *
 * All issues will be re-indexed from Jira on next polling cycle.
 */
@Document(collection = "jira_issues")
@CompoundIndexes(
    CompoundIndex(name = "connection_state_idx", def = "{'connectionDocumentId': 1, 'state': 1}"),
    CompoundIndex(name = "connection_issue_changelog_idx", def = "{'connectionDocumentId': 1, 'issueKey': 1, 'latestChangelogId': 1}", unique = true),
    CompoundIndex(name = "client_state_idx", def = "{'clientId': 1, 'state': 1}"),
)
sealed class JiraIssueIndexDocument {
    abstract val id: ObjectId
    abstract val clientId: ClientId
    abstract val connectionDocumentId: ConnectionId
    abstract val issueKey: String
    abstract val latestChangelogId: String?  // Unique ID from Jira changelog - nullable for backward compatibility
    abstract val state: String
    abstract val jiraUpdatedAt: Instant

    /**
     * NEW state - full issue data from Jira API, ready for indexing.
     */
    @TypeAlias("JiraNew")
    data class New(
        @Id override val id: ObjectId = ObjectId.get(),
        override val clientId: ClientId,
        val projectId: ProjectId? = null,
        override val connectionDocumentId: ConnectionId,
        override val issueKey: String,
        override val latestChangelogId: String?,  // Nullable for backward compatibility
        val projectKey: String?,  // Nullable - old documents may have null
        val summary: String?,  // Nullable - old documents may have null
        val description: String?,
        val issueType: String?,  // Nullable - old documents may have null
        val status: String?,  // Nullable - old documents may have null
        val priority: String?,
        val assignee: String?,
        val reporter: String?,
        val labels: List<String> = emptyList(),
        val comments: List<JiraComment> = emptyList(),
        val attachments: List<JiraAttachment> = emptyList(),
        val linkedIssues: List<String> = emptyList(),
        val createdAt: Instant?,  // Nullable - old documents may have null
        override val jiraUpdatedAt: Instant,
    ) : JiraIssueIndexDocument() {
        override val state: String = "NEW"
    }

    /**
     * INDEXED state - minimal tracking record, actual data in RAG/Graph.
     * Only keeps essentials for deduplication and sourceUrn lookup.
     */
    @TypeAlias("JiraIndexed")
    data class Indexed(
        @Id override val id: ObjectId,
        override val clientId: ClientId,
        val projectId: ProjectId? = null,
        override val connectionDocumentId: ConnectionId,
        override val issueKey: String,
        override val latestChangelogId: String?,  // Nullable for backward compatibility
        override val jiraUpdatedAt: Instant,
    ) : JiraIssueIndexDocument() {
        override val state: String = "INDEXED"
    }

    /**
     * FAILED state - same as NEW but with error, full data kept for retry.
     */
    @TypeAlias("JiraFailed")
    data class Failed(
        @Id override val id: ObjectId,
        override val clientId: ClientId,
        val projectId: ProjectId? = null,
        override val connectionDocumentId: ConnectionId,
        override val issueKey: String,
        override val latestChangelogId: String?,  // Nullable for backward compatibility
        val projectKey: String?,  // Nullable - old documents may have null
        val summary: String?,  // Nullable - old documents may have null
        val description: String?,
        val issueType: String?,  // Nullable - old documents may have null
        val status: String?,  // Nullable - old documents may have null
        val priority: String?,
        val assignee: String?,
        val reporter: String?,
        val labels: List<String> = emptyList(),
        val comments: List<JiraComment> = emptyList(),
        val attachments: List<JiraAttachment> = emptyList(),
        val linkedIssues: List<String> = emptyList(),
        val createdAt: Instant?,  // Nullable - old documents may have null
        override val jiraUpdatedAt: Instant,
        val indexingError: String,
    ) : JiraIssueIndexDocument() {
        override val state: String = "FAILED"
    }
}

/**
 * Jira comment (fetched by CentralPoller, stored in JiraIssueDocument).
 */
data class JiraComment(
    val id: String,
    val author: String,
    val body: String,
    val created: Instant,
    val updated: Instant,
)

/**
 * Jira attachment metadata (fetched by CentralPoller).
 */
data class JiraAttachment(
    val id: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val downloadUrl: String,
    val created: Instant,
)
