package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.agent.planner.Planner
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import org.springframework.stereotype.Service

@Service
class PlannerTool(
    private val planner: Planner,
    override val promptRepository: PromptRepository,
) : McpTool {
    override val name: PromptTypeEnum = PromptTypeEnum.PLANNER

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        if (taskDescription.isBlank()) {
            return ToolResult.error("Task description cannot be empty")
        }

        try {
            planner.createPlan(context, plan)
        } catch (e: Exception) {
            return ToolResult.error("Planning failed: ${e.message}. Please check the task description and context.")
        }

        return ToolResult.ok("Plann is added.")
    }
}
