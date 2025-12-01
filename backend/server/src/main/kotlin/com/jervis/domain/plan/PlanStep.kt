package com.jervis.domain.plan

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.mcp.domain.ToolResult
import org.bson.types.ObjectId

data class PlanStep(
    val id: ObjectId,
    var order: Int = -1,
    val stepToolName: ToolTypeEnum,
    val stepInstruction: String,
    var status: StepStatusEnum = StepStatusEnum.PENDING,
    var toolResult: ToolResult? = null,
)
