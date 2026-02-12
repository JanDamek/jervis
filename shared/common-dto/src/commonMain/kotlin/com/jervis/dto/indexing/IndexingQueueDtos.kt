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
