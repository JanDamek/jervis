package com.jervis.dto.user

import com.jervis.dto.AttachmentDto
import kotlinx.serialization.Serializable

@Serializable
data class UserTaskDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val state: String,
    val projectId: String? = null,
    val clientId: String,
    val sourceUri: String? = null,
    val createdAtEpochMillis: Long,
    val attachments: List<AttachmentDto> = emptyList(),
    val pendingQuestion: String? = null,
    val questionContext: String? = null,
    val priorityScore: Int? = null,
)

/** Lightweight DTO for list view — excludes content, attachments, agentCheckpointJson. */
@Serializable
data class UserTaskListItemDto(
    val id: String,
    val title: String,
    val state: String,
    val projectId: String? = null,
    val clientId: String,
    val createdAtEpochMillis: Long,
    /** Whether there is a pending question from the agent. */
    val hasPendingQuestion: Boolean = false,
    /** Short preview of the pending question (first 120 chars). */
    val pendingQuestionPreview: String? = null,
    // Work plan hierarchy
    val parentTaskId: String? = null,
    val childCount: Int = 0,
    val completedChildCount: Int = 0,
    val phase: String? = null,
    val priorityScore: Int? = null,
)

@Serializable
data class UserTaskPageDto(
    val items: List<UserTaskDto> = emptyList(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
)

/** Lightweight paginated list for UserTasksScreen list view. */
@Serializable
data class UserTaskListPageDto(
    val items: List<UserTaskListItemDto> = emptyList(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
)
