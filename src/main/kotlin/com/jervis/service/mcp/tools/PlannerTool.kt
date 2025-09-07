package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.McpToolType
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
    private val promptRepository: PromptRepository,
) : McpTool {
    override val name: String = "planner"
    override val description: String
        get() = promptRepository.getMcpToolDescription(McpToolType.PLANNER)

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult {
        if (taskDescription.isBlank()) {
            return ToolResult.error("Task description cannot be empty")
        }

        val updatedPlan =
            try {
                planner.createPlan(context, plan)
            } catch (e: Exception) {
                return ToolResult.error("Planning failed: ${e.message}. Please check the task description and context.")
            }

        val output =
            buildString {
                appendLine("🎯 Planning Results")
                appendLine("Task: ${plan.englishQuestion}")
                appendLine("Context: ${plan.contextSummary}")
                appendLine("Planning Request: $taskDescription")
                appendLine()

                if (updatedPlan.steps.isEmpty()) {
                    appendLine("⚠️ No additional steps were generated.")
                    appendLine("The plan may already be complete or the task description needs clarification.")
                } else {
                    appendLine("📋 Generated ${updatedPlan.steps.size} execution steps:")
                    appendLine()

                    updatedPlan.steps.sortedBy { it.order }.forEachIndexed { index, step ->
                        appendLine("${index + 1}. ${step.name}")
                        appendLine("   Task: ${step.taskDescription}")
                        appendLine("   Status: ${step.status}")
                        if (index < updatedPlan.steps.size - 1) {
                            appendLine()
                        }
                    }
                    appendLine()
                    appendLine("✅ Plan updated successfully. Ready for execution.")
                }

                if (plan.contextSummary?.isNotBlank() == true) {
                    appendLine()
                    appendLine("📝 Current Context Summary:")
                    appendLine(plan.contextSummary)
                }
            }

        return ToolResult.ok(output)
    }
}
