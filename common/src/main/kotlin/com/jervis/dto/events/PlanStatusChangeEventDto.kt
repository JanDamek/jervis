package com.jervis.dto.events

import kotlinx.serialization.Serializable

@Serializable
data class PlanStatusChangeEventDto(
    val eventType: String = "PLAN_STATUS_CHANGED",
    val contextId: String,
    val planId: String,
    val planStatus: String,
    val timestamp: String,
)
