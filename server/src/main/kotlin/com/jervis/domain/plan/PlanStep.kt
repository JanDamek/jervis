package com.jervis.domain.plan

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.mcp.domain.ToolResult
import org.bson.types.ObjectId

data class PlanStep(
    val id: ObjectId,
    var order: Int = -1,
    var planId: ObjectId,
    var contextId: ObjectId,
    val stepToolName: PromptTypeEnum,
    val stepInstruction: String,
    val stepDependsOn: Int = -1,
    var status: StepStatusEnum = StepStatusEnum.PENDING,
    var toolResult: ToolResult? = null,
)
