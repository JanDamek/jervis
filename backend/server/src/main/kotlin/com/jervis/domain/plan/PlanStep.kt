package com.jervis.domain.plan

import com.jervis.configuration.prompts.ToolTypeEnum
import org.bson.types.ObjectId

data class PlanStep(
    val id: ObjectId,
    var order: Int = -1,
    val stepToolName: ToolTypeEnum,
    val stepInstruction: String,
    var status: StepStatusEnum = StepStatusEnum.PENDING,
    var result: String? = null, // Simplified result as plain string
)
