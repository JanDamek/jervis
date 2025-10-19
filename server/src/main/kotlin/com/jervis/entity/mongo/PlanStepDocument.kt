package com.jervis.entity.mongo

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatusEnum
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
    val stepToolName: PromptTypeEnum,
    val stepInstruction: String,
    val stepDependsOn: Int = -1,
    var status: StepStatusEnum = StepStatusEnum.PENDING,
    var toolResult: ToolResult? = null,
) {
    fun toDomain(): PlanStep =
        PlanStep(
            id = this.id,
            order = this.order,
            planId = requireNotNull(this.planId) { "planId is required for PlanStep domain conversion" },
            contextId = requireNotNull(this.contextId) { "contextId is required for PlanStep domain conversion" },
            stepToolName = this.stepToolName,
            stepInstruction = this.stepInstruction,
            stepDependsOn = this.stepDependsOn,
            status = this.status,
            toolResult = this.toolResult,
        )

    companion object {
        fun fromDomain(planStep: PlanStep): PlanStepDocument =
            PlanStepDocument(
                id = planStep.id,
                order = planStep.order,
                planId = planStep.planId,
                contextId = planStep.contextId,
                stepToolName = planStep.stepToolName,
                stepInstruction = planStep.stepInstruction,
                stepDependsOn = planStep.stepDependsOn,
                status = planStep.status,
                toolResult = planStep.toolResult,
            )
    }
}
