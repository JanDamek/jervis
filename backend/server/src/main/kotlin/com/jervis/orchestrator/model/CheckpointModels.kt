package com.jervis.orchestrator.model

import kotlinx.serialization.Serializable

/**
 * Full agent checkpoint for stateful resume across sessions.
 *
 * Architecture:
 * - TaskDocument.agentCheckpointJson stores serialized OrchestratorCheckpoint
 * - On resume (after USER_TASK, crash, or chat continuation), agent restores:
 *   - Multi-goal decomposition state
 *   - Completed goals tracking
 *   - Current execution phase
 *   - Execution memory (intermediate findings)
 *   - User interaction state (waiting for answer?)
 *   - Conversation context (language, tone, preferences)
 *
 * Versioning:
 * - version "2.0" - full checkpoint with conversation context
 * - version "1.0" or null - legacy metadata-only checkpoint (treat as empty)
 */
@Serializable
data class OrchestratorCheckpoint(
    /**
     * Multi-goal decomposition result.
     * Contains all goals from original user query, dependency graph, etc.
     */
    val multiGoalRequest: MultiGoalRequest? = null,

    /**
     * Completed goals with their results.
     * Map: goalId -> GoalResult
     */
    val completedGoals: Map<String, GoalResult> = emptyMap(),

    /**
     * Current goal being executed (null if between goals or done).
     */
    val currentGoalId: String? = null,

    /**
     * Current phase within goal execution.
     * CONTEXT → PLANNING → EXECUTING → VALIDATING → LEARNING
     */
    val currentGoalPhase: GoalPhase? = null,

    /**
     * Execution memory - intermediate findings stored during execution.
     * Restored via ExecutionMemoryTools on resume.
     * Map: memory key -> content
     */
    val executionMemory: Map<String, String> = emptyMap(),

    /**
     * Is agent waiting for user input? (USER_TASK state)
     * If true, agent transitioned to USER_TASK and is paused.
     */
    val waitingForUserInput: Boolean = false,

    /**
     * Question agent asked user (if waitingForUserInput = true).
     * On resume, agent knows "user is answering THIS question".
     */
    val userQuestion: String? = null,

    /**
     * Where to resume after user responds or error recovery.
     */
    val resumePoint: ResumePoint? = null,

    /**
     * Conversation context - language, tone, learned preferences.
     * CRITICAL for maintaining consistent language across sessions!
     */
    val conversationContext: ConversationContext,

    /**
     * Checkpoint version for migration compatibility.
     * "2.0" = full checkpoint with conversation context
     * "1.0" or null = legacy (metadata-only)
     */
    val version: String = "2.0"
)

/**
 * Conversation context preserved across sessions.
 *
 * Critical for:
 * - Language consistency (Czech query → Czech response after restart)
 * - Tone matching (formal vs casual)
 * - Detail level (concise vs verbose)
 * - Learned preferences (frameworks, tools, styles)
 *
 * NOTE: preferences map is now DEPRECATED in favor of PreferenceService.
 * Kept for backward compatibility only. Use PreferenceTools.getAllPreferences() instead.
 */
@Serializable
data class ConversationContext(
    /**
     * Language code: "cs" (Czech), "en" (English), "sk" (Slovak)
     * Detected from user's first message, preserved across restarts.
     * CRITICAL: Agent must respond in same language after resume!
     */
    val language: String,

    /**
     * Conversation tone: "technical", "casual", "formal"
     */
    val tone: String = "technical",

    /**
     * Response detail level: "concise", "detailed", "verbose"
     */
    val detailLevel: String = "detailed",

    /**
     * Learned preferences from previous interactions.
     * Example: {"web_backend_framework" -> "Ktor", "code_style" -> "ktlint"}
     * Loaded from AgentPreferenceDocument, cached here for quick access.
     */
    val preferences: Map<String, String> = emptyMap()
)

/**
 * Where to resume execution after pause (USER_TASK) or error recovery.
 */
@Serializable
sealed class ResumePoint {
    /**
     * Resume after user answered question.
     * Agent continues goal execution with user's answer in context.
     */
    @Serializable
    data class AfterUserInput(
        val goalId: String,
        val phase: GoalPhase
    ) : ResumePoint()

    /**
     * Retry goal execution (e.g., after validation failure).
     */
    @Serializable
    data class RetryGoal(
        val goalId: String,
        val reason: String
    ) : ResumePoint()

    /**
     * Normal continuation (no special resume needed).
     */
    @Serializable
    object Continue : ResumePoint()
}

/**
 * Goal execution phase.
 * Allows agent to resume at correct step within goal.
 */
enum class GoalPhase {
    /** Gathering context for goal */
    CONTEXT,

    /** Creating execution plan */
    PLANNING,

    /** Executing plan steps */
    EXECUTING,

    /** Validating evidence */
    VALIDATING,

    /** Extracting learnings */
    LEARNING
}
