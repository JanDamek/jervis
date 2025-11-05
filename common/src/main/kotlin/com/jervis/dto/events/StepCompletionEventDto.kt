package com.jervis.dto.events

import kotlinx.serialization.Serializable

@Serializable
data class StepCompletionEventDto(
    val eventType: String = "STEP_COMPLETED",
    val contextId: String,
    val planId: String,
    val stepId: String,
    val stepName: String,
    val stepStatus: String,
    val timestamp: String,
)
