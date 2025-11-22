package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatusEnum
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Consolidates a range of plan steps into a single summary step.
 * This is a parametric tool (NO LLM) for context management during long planning sessions.
 * Plan is runtime-only, no persistence.
 */
@Service
class ConsolidateStepsTool(
    override val promptRepository: PromptRepository,
) : McpTool<ConsolidateStepsTool.ConsolidateStepsParams> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name = ToolTypeEnum.CONSOLIDATE_STEPS_TOOL

    override val descriptionObject =
        ConsolidateStepsParams(
            stepRangeFrom = 0,
            stepRangeTo = 2,
            summaryText = "Summarize initial discovery and environment setup",
        )

    @Serializable
    data class ConsolidateStepsParams(
        val stepRangeFrom: Int,
        val stepRangeTo: Int,
        val summaryText: String,
    )

    override suspend fun execute(
        plan: Plan,
        request: ConsolidateStepsParams,
    ): ToolResult {
        val params = request

        // Validate parameters
        if (params.stepRangeFrom < 0 || params.stepRangeTo < params.stepRangeFrom) {
            return ToolResult.error("Invalid step range: ${params.stepRangeFrom} to ${params.stepRangeTo}")
        }

        if (params.stepRangeTo >= plan.steps.size) {
            return ToolResult.error(
                "Step range out of bounds: ${params.stepRangeTo} >= ${plan.steps.size}",
            )
        }

        if (params.summaryText.isBlank()) {
            return ToolResult.error("Summary text cannot be blank")
        }

        // Extract steps to consolidate
        plan.steps.subList(params.stepRangeFrom, params.stepRangeTo + 1)
        val consolidatedCount = params.stepRangeTo - params.stepRangeFrom + 1
        val result =
            ToolResult.success(
                toolName = name.name,
                summary = "Consolidated $consolidatedCount steps into summary",
                content =
                    buildString {
                        appendLine("Successfully consolidated steps ${params.stepRangeFrom} to ${params.stepRangeTo}")
                        appendLine()
                        appendLine("Summary: ${params.summaryText}")
                    },
            )
        // Create summary step
        val summaryStep =
            PlanStep(
                id = ObjectId.get(),
                order = params.stepRangeFrom,
                stepToolName = ToolTypeEnum.CONSOLIDATE_STEPS_TOOL,
                stepInstruction = "CONSOLIDATED: ${params.summaryText}",
                toolResult = result,
                status = StepStatusEnum.DONE,
            )

        // Remove consolidated steps and insert summary
        val newSteps =
            buildList {
                addAll(plan.steps.subList(0, params.stepRangeFrom))
                add(summaryStep)
                addAll(plan.steps.subList(params.stepRangeTo + 1, plan.steps.size))
            }

        // Reorder remaining steps
        val reorderedSteps =
            newSteps.mapIndexed { index, step ->
                step.copy(order = index)
            }

        plan.steps = reorderedSteps

        logger.info {
            "CONSOLIDATE_STEPS_SUCCESS: Consolidated $consolidatedCount steps (${params.stepRangeFrom}-${params.stepRangeTo}) in plan ${plan.id}"
        }

        return result
    }
}
