package com.jervis.dto.task

import com.jervis.dto.chat.AttachmentDto
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
    // Phase 4 — unified TasksScreen fields
    /** Display name for UI (defaults to "Unnamed Task" on backend). */
    val taskName: String = "Unnamed Task",
    /**
     * Czech UI label derived from sourceUrn.scheme() — "Email", "WhatsApp",
     * "Schůzka", … See SourceUrn.uiLabel() in common-services.
     */
    val sourceLabel: String = "",
    /** sourceUrn scheme prefix (e.g. "email", "whatsapp") for client-side filter chips. */
    val sourceScheme: String = "",
    /** Active question if state=USER_TASK. Null otherwise. */
    val pendingUserQuestion: String? = null,
    /** Phase 3 re-entrant qualifier flag — task is awaiting (re-)qualification. */
    val needsQualification: Boolean = false,
)

/** Paginated result for PendingTasksScreen — merges listTasks + countTasks into a single RPC call. */
@Serializable
data class PagedPendingTasksResult(
    val items: List<PendingTaskDto>,
    val totalCount: Long,
    val page: Int,
    val pageSize: Int,
)
