package com.jervis.orchestrator.model

import kotlinx.serialization.Serializable

/**
 * Ordered execution plan created by PlannerAgent.
 *
 * No task IDs, no dependency tracking - order of steps IS the execution order.
 * Koog orchestrator executes steps sequentially.
 */
@Serializable
data class OrderedPlan(
    /** Steps to execute (in order) */
    val steps: List<PlanStep>,

    /** Reasoning behind plan (for debugging/logging) */
    val reasoning: String = "",
)

/**
 * Single atomic step in execution plan.
 */
@Serializable
data class PlanStep(
    /** Sequential ID (1, 2, 3, ...) */
    val id: String,

    /** Action type: "coding", "verify", "rag_ingest", "jira_update", "email_send", "research", etc. */
    val action: String,

    /** Clear description of what to do (1-2 sentences) */
    val description: String,

    /** Executor hint: "aider", "openhands", "internal", "research" */
    val executor: String,

    /** Inputs for this step (e.g., targetFiles for Aider, query for research) */
    val inputs: Map<String, String> = emptyMap(),

    /** Expected output description (for verification) */
    val expectedOutput: String = "",
)
