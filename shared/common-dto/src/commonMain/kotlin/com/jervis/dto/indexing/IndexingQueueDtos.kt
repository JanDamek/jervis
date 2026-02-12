package com.jervis.dto.indexing

import kotlinx.serialization.Serializable

/**
 * Type of item in the indexing queue.
 */
@Serializable
enum class IndexingItemType {
    GIT_COMMIT,
    EMAIL,
    BUGTRACKER_ISSUE,
    WIKI_PAGE,
}

/**
 * A single item in the indexing queue (pending or indexed).
 */
@Serializable
data class IndexingQueueItemDto(
    val id: String,
    val type: IndexingItemType,
    val connectionName: String,
    val connectionId: String,
    val clientName: String,
    val projectName: String?,
    /** Commit message / email subject / issue key+summary / wiki page title */
    val title: String,
    /** ISO 8601 timestamp */
    val createdAt: String?,
    /** NEW, INDEXED, FAILED, INDEXING */
    val state: String,
    val errorMessage: String? = null,
    /** Source URN for KB reference (e.g. "jira::conn:abc,issueKey:PROJ-123") */
    val sourceUrn: String? = null,
)

/**
 * Paginated response of indexing queue items.
 */
@Serializable
data class IndexingQueuePageDto(
    val items: List<IndexingQueueItemDto>,
    val totalCount: Long,
    val page: Int,
    val pageSize: Int,
)

// ── Dashboard DTOs (grouped by connection) ──

/**
 * Group of pending indexing items belonging to a single connection.
 * Used in the dashboard view to show items grouped by their source.
 */
@Serializable
data class ConnectionIndexingGroupDto(
    /** Connection ID, empty string for Git commits (no connection entity). */
    val connectionId: String,
    val connectionName: String,
    /** ProviderEnum name or "GIT" for synthetic group. */
    val provider: String,
    val capabilities: List<String>,
    /** ISO 8601 – last time this connection was polled. */
    val lastPolledAt: String?,
    /** ISO 8601 – computed: lastPolledAt + intervalMinutes. */
    val nextCheckAt: String?,
    /** Current polling interval in minutes for the primary capability. */
    val intervalMinutes: Int,
    /** Pending items in this group (may be truncated). */
    val items: List<IndexingQueueItemDto>,
    /** Total pending item count for this connection. */
    val totalItemCount: Int,
)

/**
 * KB queue item with timing information.
 * Shows items that have been sent to KB with arrival time and wait duration.
 */
@Serializable
data class KbQueueItemDto(
    val id: String,
    val type: IndexingItemType,
    val title: String,
    val connectionName: String,
    val clientName: String,
    /** Source URN for KB reference. */
    val sourceUrn: String?,
    /** ISO 8601 – when the item was marked INDEXED. */
    val indexedAt: String?,
    /** How long waiting for KB processing (minutes). */
    val waitingDurationMinutes: Long?,
)

/**
 * Combined dashboard response: pending items grouped by connection + KB queue.
 * Replaces the three separate calls (connections, pending, indexed) with a single call.
 */
@Serializable
data class IndexingDashboardDto(
    val connectionGroups: List<ConnectionIndexingGroupDto>,
    val kbQueue: List<KbQueueItemDto>,
    val kbQueueTotalCount: Long,
)
