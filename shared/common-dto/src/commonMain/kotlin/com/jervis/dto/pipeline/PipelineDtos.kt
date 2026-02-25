package com.jervis.dto.pipeline

import kotlinx.serialization.Serializable

/**
 * Action type derived from qualifier analysis.
 * Determines what kind of task should be created and how it should be processed.
 */
@Serializable
enum class ActionType {
    /** Code change needed (bug fix, feature implementation). */
    CODE_FIX,

    /** Existing code needs review (PR, MR, code quality). */
    CODE_REVIEW,

    /** Email or message needs a response. */
    RESPOND_EMAIL,

    /** Documentation needs to be created or updated. */
    UPDATE_DOCS,

    /** New ticket/issue should be created in external bugtracker. */
    CREATE_TICKET,

    /** Content needs further analysis before deciding on action. */
    INVESTIGATE,

    /** Informational only — no action needed, just notify user. */
    NOTIFY_ONLY,
}

/**
 * Estimated complexity of the task, used for routing and approval decisions.
 * Affects whether the task goes through planning and whether user approval is required.
 */
@Serializable
enum class EstimatedComplexity {
    /** One-liner fix, typo, config change. No planning needed. */
    TRIVIAL,

    /** Small focused change, <50 lines. Auto-dispatch without planning. */
    SIMPLE,

    /** Multi-file change, needs planning. May require user approval. */
    MEDIUM,

    /** Major refactoring, new feature, architectural change. Requires user approval. */
    COMPLEX,
}

/**
 * Extended qualification result with structured fields for autonomous pipeline routing.
 * These fields augment the existing FullIngestResult with typed enums instead of free-form strings.
 */
@Serializable
data class QualificationEnhancedResult(
    /** Typed action to take. */
    val actionType: ActionType = ActionType.NOTIFY_ONLY,

    /** Estimated task complexity for routing. */
    val estimatedComplexity: EstimatedComplexity = EstimatedComplexity.SIMPLE,

    /** Recommended agent to handle the task. */
    val suggestedAgent: SuggestedAgent = SuggestedAgent.NONE,

    /** Files likely affected by the change (paths relative to repo root). */
    val affectedFiles: List<String> = emptyList(),

    /** Related KB node IDs for context enrichment. */
    val relatedKbNodes: List<String> = emptyList(),
)

/**
 * Which agent type is recommended for handling the task.
 */
@Serializable
enum class SuggestedAgent {
    /** Coding agent (Aider, OpenHands, Claude, Junie). */
    CODING,

    /** Full orchestrator (multi-step, multi-tool). */
    ORCHESTRATOR,

    /** No agent needed (notification only). */
    NONE,
}

/**
 * Priority score holder for task ordering.
 * Higher score = higher priority in the background queue.
 */
@Serializable
data class TaskPriorityInfo(
    /** Priority score 0–100. Higher = more urgent. */
    val score: Int = 50,

    /** Human-readable reason for the priority. */
    val reason: String = "",
)
