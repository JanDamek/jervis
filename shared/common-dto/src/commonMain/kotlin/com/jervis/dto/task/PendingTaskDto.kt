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
    /** Context that explains the pending question, if any. */
    val userQuestionContext: String? = null,
    /** Phase 3 re-entrant qualifier flag — task is awaiting (re-)qualification. */
    val needsQualification: Boolean = false,
    // === Qualifier output (Phase 3 re-entrant qualifier results) ===
    /** Short LLM-extracted overview of the source content (from KB ingestion). */
    val kbSummary: String? = null,
    /** Graph entities extracted by KB during ingestion. */
    val kbEntities: List<String> = emptyList(),
    /** Priority score 0–100 assigned by the qualifier. */
    val priorityScore: Int? = null,
    /** Human-readable reason for the qualifier's priority decision. */
    val priorityReason: String? = null,
    /** Inferred action type (TECHNICAL / ANALYSIS / REVIEW / COMMUNICATION / …). */
    val actionType: String? = null,
    /** Inferred complexity (TRIVIAL / SIMPLE / MEDIUM / COMPLEX / CRITICAL). */
    val estimatedComplexity: String? = null,
    /** Free-text context the qualifier prepared for the orchestrator. */
    val qualifierContextSummary: String? = null,
    /** Free-text approach the qualifier suggests for the orchestrator. */
    val qualifierSuggestedApproach: String? = null,
    /** Last qualifier step message (for compact "what is the qualifier doing" hint). */
    val lastQualificationStep: String? = null,
    /** 2-3 sentence overview set by the qualifier agent — shown in brief and related-tasks list. */
    val summary: String? = null,
    /** Absolute deadline ISO-8601; null = no urgency pressure. Drives sidebar ordering. */
    val deadlineIso: String? = null,
    /** Observed user presence snapshot at task creation; null when not recorded. */
    val userPresence: String? = null,
)

/** Paginated result for PendingTasksScreen — merges listTasks + countTasks into a single RPC call. */
@Serializable
data class PagedPendingTasksResult(
    val items: List<PendingTaskDto>,
    val totalCount: Long,
    val page: Int,
    val pageSize: Int,
)
