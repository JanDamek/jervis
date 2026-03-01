package com.jervis.dto

import kotlinx.serialization.Serializable

@Serializable
data class PendingTaskDto(
    val id: String,
    val taskType: String,
    val content: String,
    // Optional: some tasks are client-scoped only and do not belong to a specific project
    val projectId: String? = null,
    val clientId: String,
    val createdAt: String,
    val state: String,
    val attachments: List<AttachmentDto> = emptyList(),
    // Work plan hierarchy
    val parentTaskId: String? = null,
    val childCount: Int = 0,
    val completedChildCount: Int = 0,
    val phase: String? = null,
)

/** Paginated result for PendingTasksScreen — merges listTasks + countTasks into a single RPC call. */
@Serializable
data class PagedPendingTasksResult(
    val items: List<PendingTaskDto>,
    val totalCount: Long,
    val page: Int,
    val pageSize: Int,
)
