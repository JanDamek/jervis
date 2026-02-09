package com.jervis.dto

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
    val processingMode: String, // "FOREGROUND" or "BACKGROUND"
    val queuePosition: Int? = null,
)
