package com.jervis.domain.plan

import com.jervis.service.mcp.domain.ToolResult
import org.bson.types.ObjectId

data class PlanStep(
    val id: ObjectId,
    var order: Int = -1,
    var planId: ObjectId,
    var contextId: ObjectId,
    val name: String,
    val taskDescription: String,
    var status: StepStatus = StepStatus.PENDING,
    var output: ToolResult? = null,
)
