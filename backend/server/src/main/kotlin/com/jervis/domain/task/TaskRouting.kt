package com.jervis.domain.task

/**
 * Task routing decision from KoogQualifierAgent (CPU).
 *
 * Graph-Based Routing Architecture:
 * - DONE: Simple document, no further analysis needed (RAG + Graph already created)
 * - READY_FOR_GPU: Complex analysis needed, route to KoogWorkflowAgent (GPU)
 */
data class TaskRouting(
    /** Routing decision: DONE or READY_FOR_GPU */
    val decision: TaskRoutingDecision,
    /** Reason for routing decision */
    val reason: String,
    /** Context summary for GPU agent (required if READY_FOR_GPU) */
    val contextSummary: String? = null,
)

enum class TaskRoutingDecision {
    /** Document processed, no GPU analysis needed */
    DONE,

    /** Complex analysis required, send to GPU workflow agent */
    READY_FOR_GPU,
}
