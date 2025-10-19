package com.jervis.dto

import com.jervis.common.Constants
import com.jervis.domain.plan.StepStatusEnum
import kotlinx.serialization.Serializable

@Serializable
data class PlanStepDto(
    val id: String = Constants.GLOBAL_ID_STRING,
    val order: Int = -1,
    val planId: String,
    val contextId: String,
    val stepToolName: String,
    val stepInstruction: String,
    val stepDependsOn: Int = -1,
    val status: StepStatusEnum = StepStatusEnum.PENDING,
    val toolResult: String? = null,
)
