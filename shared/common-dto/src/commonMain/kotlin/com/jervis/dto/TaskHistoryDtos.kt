package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * A single node in the orchestrator pipeline execution history.
 */
@Serializable
data class TaskHistoryNodeDto(
    val node: String,
    val label: String,
)

/**
 * A completed task entry for the agent history display.
 */
@Serializable
data class TaskHistoryEntryDto(
    val id: String,
    val taskId: String,
    val taskPreview: String,
    val projectName: String?,
    val clientName: String?,
    val startedAt: String?,
    val completedAt: String,
    val status: String, // "done", "error", "interrupted"
    val processingMode: String, // "FOREGROUND", "BACKGROUND"
    val nodes: List<TaskHistoryNodeDto> = emptyList(),
)

/**
 * Paginated response for task history.
 */
@Serializable
data class TaskHistoryPageDto(
    val items: List<TaskHistoryEntryDto>,
    val totalCount: Long,
    val hasMore: Boolean,
)
