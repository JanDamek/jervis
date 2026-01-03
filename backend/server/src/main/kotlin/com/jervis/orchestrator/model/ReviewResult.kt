package com.jervis.orchestrator.model

import kotlinx.serialization.Serializable

/**
 * Result of completeness review by ReviewerAgent.
 *
 * Determines if orchestrator should:
 * - Finish and compose answer (complete=true)
 * - Execute extraSteps and iterate (complete=false)
 */
@Serializable
data class ReviewResult(
    /** Is the answer complete? Covers all parts of original query? */
    val complete: Boolean,

    /** Parts of original query not yet addressed */
    val missingParts: List<String> = emptyList(),

    /** Additional steps to execute (if incomplete) */
    val extraSteps: List<PlanStep> = emptyList(),

    /** Reviewer's reasoning (for debugging/logging) */
    val reasoning: String = "",

    /** Security/constraint violations found (e.g., "git push detected") */
    val violations: List<String> = emptyList(),
) {
    /** Should orchestrator iterate? */
    val needsIteration: Boolean = !complete && extraSteps.isNotEmpty()

    /** Is answer ready to return to user? */
    val readyForUser: Boolean = complete && violations.isEmpty()
}
