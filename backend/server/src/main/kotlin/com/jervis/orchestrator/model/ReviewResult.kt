package com.jervis.orchestrator.model

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of completeness review by ReviewerAgent.
 *
 * Determines if orchestrator should:
 * - Finish and compose answer (complete=true)
 * - Create new plan based on missingParts and iterate (complete=false)
 */
@Serializable
@SerialName("ReviewResult")
@LLMDescription("Completeness assessment of execution results. Determines if goal is fully satisfied or if additional work is needed. Includes detected security/constraint violations.")
data class ReviewResult(
    @property:LLMDescription("Is the goal complete? True = all parts of original query addressed and deliverables ready. False = missing information or incomplete execution.")
    val complete: Boolean,

    @property:LLMDescription("Parts of original query not yet addressed - each item describes what's missing (e.g., 'User requested test coverage but no tests were run')")
    val missingParts: List<String> = emptyList(),

    @property:LLMDescription("Security/constraint violations detected during execution (e.g., 'git push attempted but not allowed', 'API key exposed in code')")
    val violations: List<String> = emptyList(),

    @property:LLMDescription("Reviewer's reasoning explaining the completeness assessment and any concerns (for debugging/logging)")
    val reasoning: String = "",
)
