package com.jervis.orchestrator.model

import kotlinx.serialization.Serializable

/**
 * Result of completeness review by ReviewerAgent.
 *
 * Determines if orchestrator should:
 * - Finish and compose answer (complete=true)
 * - Create new plan based on missingParts and iterate (complete=false)
 */
@Serializable
data class ReviewResult(
    /** Is the answer complete? Covers all parts of original query? */
    val complete: Boolean,

    /** Parts of original query not yet addressed */
    val missingParts: List<String> = emptyList(),

    /** Security/constraint violations found (e.g., "git push detected") */
    val violations: List<String> = emptyList(),

    /** Reviewer's reasoning (for debugging/logging) */
    val reasoning: String = "",
)
