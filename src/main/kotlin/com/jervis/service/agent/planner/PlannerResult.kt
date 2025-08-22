package com.jervis.service.agent.planner

/**
 * Planner result including detected scope and translated text.
 */
data class PlannerResult(
    val message: String,
    val chosenProject: String,
    val detectedClient: String? = null,
    val detectedProject: String? = null,
    val englishText: String? = null,
    val shouldContinue: Boolean = false,
)
