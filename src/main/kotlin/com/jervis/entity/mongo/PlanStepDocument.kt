package com.jervis.entity.mongo

import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatus
import com.jervis.service.mcp.domain.ToolResult
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "plan_steps")
data class PlanStepDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    var order: Int = -1,
    var planId: ObjectId? = null,
    var contextId: ObjectId? = null,
    val name: String,
    val taskDescription: String,
    val stepBack: List<Int> = emptyList(),
    var status: StepStatus = StepStatus.PENDING,
    var output: ToolResult? = null,
) {
    fun toDomain(): PlanStep =
        PlanStep(
            id = this.id,
            order = this.order,
            planId = this.planId!!,
            contextId = this.contextId!!,
            stepToolName = this.name,
            stepInstruction = this.taskDescription,
            stepDependsOn = this.stepBack,
            status = this.status,
            toolResult = this.output,
        )

    companion object {
        fun fromDomain(planStep: PlanStep): PlanStepDocument =
            PlanStepDocument(
                id = planStep.id,
                order = planStep.order,
                planId = planStep.planId,
                contextId = planStep.contextId,
                name = planStep.stepToolName,
                taskDescription = planStep.stepInstruction,
                stepBack = planStep.stepDependsOn,
                status = planStep.status,
                output = planStep.toolResult,
            )
    }
}
