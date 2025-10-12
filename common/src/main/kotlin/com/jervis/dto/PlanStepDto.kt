package com.jervis.dto

import com.jervis.domain.plan.StepStatus
import kotlinx.serialization.Serializable

@Serializable
data class PlanStepDto(
    val id: String,
    val order: Int = -1,
    val planId: String,
    val contextId: String,
    val stepToolName: String,
    val stepInstruction: String,
    val stepDependsOn: Int = -1,
    val status: StepStatus = StepStatus.PENDING,
    val toolResult: String? = null,
)
