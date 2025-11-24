package com.jervis.entity.jira

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Jira issue document with COMPLETE data for indexing.
 *
 * Architecture:
 * - CentralPoller fetches FULL issue data from API and saves here as NEW
 * - JiraContinuousIndexer reads from MongoDB (no API calls) and indexes to RAG
 * - MongoDB is staging area between API and RAG
 *
 * Note: connectionId refers to Connection.id (HttpConnection for Atlassian)
 */
@Document(collection = "jira_issues")
@CompoundIndexes(
    CompoundIndex(name = "connection_issue_unique", def = "{'connectionId': 1, 'issueKey': 1}", unique = true),
    CompoundIndex(name = "client_idx", def = "{'clientId': 1}"),
    CompoundIndex(name = "connection_state_idx", def = "{'connectionId': 1, 'state': 1}"),
    CompoundIndex(name = "state_updated_idx", def = "{'state': 1, 'updatedAt': -1}"),
)
data class JiraIssueIndexDocument(
    @Id
    val id: ObjectId = ObjectId.get(),

    /** Connection ID (Connection.HttpConnection) */
    @Indexed
    val connectionId: ObjectId,

    /** Client ID */
    @Indexed
    val clientId: ObjectId,

    /** Jira issue key (e.g., "PROJ-123") */
    @Indexed
    val issueKey: String,

    /** Project key (e.g., "PROJ") */
    val projectKey: String,

    // === FULL CONTENT (fetched by CentralPoller) ===

    /** Issue summary/title */
    val summary: String,

    /** Full description in markdown/text */
    val description: String? = null,

    /** Issue type (Bug, Task, Story, etc.) */
    val issueType: String,

    /** Current status (To Do, In Progress, Done, etc.) */
    val status: String,

    /** Priority (High, Medium, Low, etc.) */
    val priority: String? = null,

    /** Assignee account ID */
    val assignee: String? = null,

    /** Reporter account ID */
    val reporter: String? = null,

    /** Labels */
    val labels: List<String> = emptyList(),

    /** Comments (full text) */
    val comments: List<JiraComment> = emptyList(),

    /** Attachments metadata */
    val attachments: List<JiraAttachment> = emptyList(),

    /** Links to other issues */
    val linkedIssues: List<String> = emptyList(),

    /** When issue was created in Jira */
    val createdAt: Instant,

    /** When issue was last updated in Jira */
    val jiraUpdatedAt: Instant,

    // === STATE MANAGEMENT ===

    /** Indexing state: NEW, INDEXING, INDEXED, FAILED */
    @Indexed
    val state: String = "NEW",

    /** When document was created/updated in our DB */
    @Indexed
    val updatedAt: Instant = Instant.now(),

    /** When issue was last indexed to RAG */
    val lastIndexedAt: Instant? = null,

    /** Is archived (deleted from Jira or manually archived) */
    val archived: Boolean = false,

    // === INDEXING STATS (updated by ContinuousIndexer) ===

    /** Number of RAG chunks created */
    val totalRagChunks: Int = 0,

    /** Number of comments indexed */
    val commentChunkCount: Int = 0,

    /** Number of attachments indexed */
    val attachmentCount: Int = 0,
)

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
