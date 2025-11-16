package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.service.agent.planner.Planner
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import org.springframework.stereotype.Service

@Service
class PlanningCreatePlanTool(
    private val planner: Planner,
    override val promptRepository: PromptRepository,
) : McpTool {
    override val name: PromptTypeEnum = PromptTypeEnum.PLANNING_CREATE_PLAN_TOOL

    override suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        if (taskDescription.isBlank()) {
            return ToolResult.error("Task description cannot be empty")
        }

        val nextStepsResponse = planner.suggestNextSteps(plan)

        return ToolResult.success(
            toolName = "PLANNER",
            summary = "Planning completed successfully",
            content = "Suggested ${nextStepsResponse.nextSteps.size} next steps for the current plan based on context and progress.",
        )
    }
}
