package com.jervis.service.agent.toolreasoning

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatusEnum
import com.jervis.mcp.McpToolRegistry
import com.jervis.service.agent.planner.Planner
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.gateway.processing.PromptBuilderService
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Tool Reasoning Service - Phase 2 of a planning process.
 * Takes descriptive requirements from Planner and maps them to specific MCP tools.
 * Uses LLM to reason about which tools and parameters are needed.
 */
@Service
class ToolReasoningService(
    private val llmGateway: LlmGateway,
    private val mcpToolRegistry: McpToolRegistry,
    private val promptBuilderService: PromptBuilderService,
) {
    private val logger = KotlinLogging.logger {}

    @Serializable
    data class ToolSelection(
        val toolName: String = "",
        val reasoning: String = "",
        val parameters: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class ToolReasoningResponse(
        val selections: List<ToolSelection> = emptyList(),
    )

    /**
     * Convert descriptive requirements from Planner into executable PlanSteps with tools.
     * Phase 2: Tool Reasoning decides HOW to fulfill requirements using available MCP tools.
     */
    suspend fun mapRequirementsToTools(
        requirements: List<Planner.NextStepRequest>,
        plan: Plan,
    ): List<PlanStep> {
        if (requirements.isEmpty()) {
            logger.info { "[TOOL_REASONING] No requirements to process for plan ${plan.id}" }
            return emptyList()
        }

        logger.info { "[TOOL_REASONING_START] planId=${plan.id} requirements=${requirements.size}" }

        val availableTools = mcpToolRegistry.getAllTools()
        val toolDescriptions =
            availableTools.joinToString("\n") { tool ->
                "${tool.name.name}: ${
                    promptBuilderService.appendJsonModeInstructions(
                        tool.description,
                        tool.descriptionObject,
                    )
                }"
            }

        val mappingValues =
            mapOf(
                "requirements" to requirements.joinToString("\n") { "- ${it.description}" },
                "availableTools" to toolDescriptions,
                "planContext" to buildPlanContext(plan),
            )

        val parsedResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.TOOL_REASONING,
                responseSchema = ToolReasoningResponse(),
                correlationId = plan.correlationId,
                mappingValue = mappingValues,
                backgroundMode = plan.backgroundMode,
            )

        val toolReasoningOut = parsedResponse.result

        // Audit: log tool reasoning decisions
        logger.info {
            "[TOOL_REASONING_AUDIT] planId=${plan.id} selections=${toolReasoningOut.selections.map { it.toolName to it.reasoning }}"
        }

        // Convert selections to PlanSteps
        val newSteps =
            toolReasoningOut.selections.mapIndexed { index, selection ->
                val tool = resolveToolByName(selection.toolName)
                val stepInstruction =
                    buildStepInstruction(
                        requirements.getOrNull(index)?.description ?: "Execute task",
                        selection.parameters,
                    )

                PlanStep(
                    id = ObjectId(),
                    order = plan.steps.size + index + 1,
                    stepToolName = tool,
                    stepInstruction = stepInstruction,
                    status = StepStatusEnum.PENDING,
                )
            }

        logger.info {
            "[TOOL_REASONING_RESULT] planId=${plan.id} createdSteps=${newSteps.size} tools=${newSteps.map { it.stepToolName.name }}"
        }
        return newSteps
    }

    private fun resolveToolByName(toolName: String): ToolTypeEnum {
        val exactMatch =
            ToolTypeEnum.entries.find {
                it.name.equals(toolName, ignoreCase = true)
            }
        if (exactMatch != null) {
            return exactMatch
        }

        throw IllegalArgumentException("ToolReasoningService: Unknown tool name '$toolName'")
    }

    private fun buildStepInstruction(
        description: String,
        parameters: Map<String, String>,
    ): String =
        if (parameters.isEmpty()) {
            description
        } else {
            buildString {
                appendLine(description)
                appendLine()
                appendLine("Parameters:")
                parameters.forEach { (key, value) ->
                    appendLine("  $key: $value")
                }
            }
        }

    private fun buildPlanContext(plan: Plan): String {
        val completedSteps = plan.steps.count { it.status == StepStatusEnum.DONE }
        val totalSteps = plan.steps.size

        return buildString {
            appendLine("Plan Context:")
            appendLine("- Task: ${plan.taskInstruction}")
            appendLine("- Progress: $completedSteps/$totalSteps steps completed")

            if (completedSteps > 0) {
                appendLine()
                appendLine("Recent completed steps:")
                plan.steps
                    .filter { it.status == StepStatusEnum.DONE }
                    .takeLast(3)
                    .forEach { step ->
                        appendLine("  - ${step.stepToolName.name}: ${step.stepInstruction.take(100)}")
                    }
            }
        }
    }
}
