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
 * Order in list determines execution order (no IDs needed).
 */
@Serializable
data class PlanStep(
    /** Action type: "coding", "verify", "rag_ingest", "jira_update", "email_send", "research", etc. */
    val action: String,

    /** Executor hint: "aider", "openhands", "internal" */
    val executor: String,

    /** Clear description of what to do (1-2 sentences) */
    val description: String,
)
