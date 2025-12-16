package com.jervis.koog.qualifier.types

import kotlinx.serialization.Serializable

/**
 * Typed result z TaskTools.routeTask() tool.
 * Nahrazuje callback pattern (onRoutingCaptured).
 */
@Serializable
data class RouteTaskResult(
    /**
     * Routing decision: "DONE" | "LIFT_UP"
     */
    val routing: String,

    /**
     * Úspěšnost operace
     */
    val success: Boolean,

    /**
     * Volitelná zpráva (např. co se stalo s taskem)
     */
    val message: String
)
