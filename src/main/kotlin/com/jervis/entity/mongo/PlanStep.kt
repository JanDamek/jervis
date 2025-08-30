package com.jervis.entity.mongo

import com.jervis.domain.plan.StepStatus
import com.jervis.service.mcp.domain.ToolResult
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "plan_steps")
data class PlanStep(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    var order: Int = -1,
    var planId: ObjectId? = null,
    var contextId: ObjectId? = null,
    val name: String,
    val parameters: String,
    var status: StepStatus = StepStatus.PENDING,
    var output: ToolResult? = null,
)
