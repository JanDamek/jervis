package com.jervis.dto.task

import kotlinx.serialization.Serializable

/**
 * Response DTO for dual-queue pending tasks display.
 * Contains both FOREGROUND and BACKGROUND queues with the running task.
 */
@Serializable
data class PendingTasksDto(
    val foreground: List<PendingTaskItemDto> = emptyList(),
    val background: List<PendingTaskItemDto> = emptyList(),
    val runningTask: PendingTaskItemDto? = null,
    val backgroundTotalCount: Long = 0,
)

/**
 * Paginated response for background tasks (infinite scroll in UI).
 */
@Serializable
data class PendingTasksPageDto(
    val items: List<PendingTaskItemDto>,
    val totalCount: Long,
    val hasMore: Boolean,
)

/**
 * Single pending task item for queue display.
 * Used in AgentWorkloadScreen to show queue items with reorder/move controls.
 */
@Serializable
data class PendingTaskItemDto(
    val taskId: String,
    val projectName: String,
    val preview: String,
    val taskType: String,
    val processingMode: String, // "FOREGROUND", "BACKGROUND", or "IDLE"
    val queuePosition: Int? = null,
    /** ISO-8601 absolute deadline; null = no deadline pressure (BATCH). */
    val deadlineIso: String? = null,
    /** Observed user presence snapshot name; null when not recorded. */
    val userPresence: String? = null,
)
