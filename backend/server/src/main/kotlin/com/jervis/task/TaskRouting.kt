package com.jervis.task

/**
 * Task routing decision from qualifier (CPU).
 *
 * Graph-Based Routing Architecture:
 * - DONE: Simple document, no further analysis needed (RAG + Graph already created)
 * - QUEUED: Complex analysis needed, route to Python orchestrator
 */
data class TaskRouting(
    /** Routing decision: DONE or QUEUED */
    val decision: TaskRoutingDecision,
    /** Reason for routing decision */
    val reason: String,
    /** Context summary for orchestrator (required if QUEUED) */
    val contextSummary: String? = null,
)

enum class TaskRoutingDecision {
    /** Document processed, no orchestration needed */
    DONE,

    /** Complex analysis required, send to orchestrator */
    QUEUED,
}
