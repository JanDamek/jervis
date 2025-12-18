package com.jervis.koog.qualifier.state

/**
 * Final routing decision (Phase 4).
 */
enum class RoutingType {
    /** Task is complete and indexed - no further action needed */
    DONE,

    /** Task requires complex analysis/actions - route to GPU agent */
    LIFT_UP,
}

/**
 * Routing decision with reason.
 */
data class RoutingDecision(
    val routing: RoutingType,
    val reason: String,
)
