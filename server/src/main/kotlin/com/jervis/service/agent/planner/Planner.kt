package com.jervis.service.agent.planner

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatusEnum
import com.jervis.service.gateway.core.LlmGateway
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Simplified iterative Planner that suggests next steps based on the current plan.
 * Replaces complex multiphase planning with a dynamic, context-driven approach.
 */
@Service
class Planner(
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    lateinit var availableTools: String
    lateinit var toolDescriptions: String

    @Serializable
    data class StepDto(
        val stepToolName: String = "",
        val stepInstruction: String = "",
        val stepDependsOn: Int = -1,
    )

    /**
     * Suggests next steps based on current plan progress.
     * Uses iterative approach instead of complex goal-based planning.
     */
    suspend fun suggestNextSteps(plan: Plan): List<PlanStep> {
        logger.info { "Suggesting next steps for plan ${plan.id}" }

        val parsedResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.PLANNING_CREATE_PLAN_TOOL,
                quick = plan.quick,
                responseSchema = listOf(StepDto()),
                mappingValue = buildStepsContext(plan),
                backgroundMode = plan.backgroundMode,
            )

        // Store think content if present
        parsedResponse.thinkContent?.let { thinkContent ->
            logger.info { "[PLANNER_THINK] Plan ${plan.id}: think content captured (${thinkContent.length} chars)" }
            plan.thinkingSequence += thinkContent
        }

        val newSteps =
            parsedResponse.result.mapIndexed { index, dto ->
                createPlanStep(dto, plan.id, plan.steps.size + index + 1)
            }

        logger.info { "Suggested ${newSteps.size} next steps for plan ${plan.id}" }
        return newSteps
    }

    private fun createPlanStep(
        dto: StepDto,
        planId: ObjectId,
        order: Int,
    ): PlanStep {
        val validToolName =
            PromptTypeEnum.fromString(dto.stepToolName)
                ?: correctToolName(dto.stepToolName)
                ?: run {
                    logger.error { "[PLANNER_VALIDATION] Invalid step tool name '${dto.stepToolName}', no matching enum or alias found" }
                    throw IllegalArgumentException("Unknown tool: ${dto.stepToolName}")
                }

        return PlanStep(
            id = ObjectId(),
            order = order,
            stepToolName = validToolName,
            stepInstruction = dto.stepInstruction,
            stepDependsOn = dto.stepDependsOn,
            status = StepStatusEnum.PENDING,
        )
    }

    /**
     * Attempts to correct common tool name mistakes made by LLM models.
     * Maps invalid tool names to valid PromptTypeEnum constants.
     */
    private fun correctToolName(invalidToolName: String): PromptTypeEnum? {
        val corrections =
            mapOf(
                "searchCodebase" to PromptTypeEnum.PROJECT_EXPLORE_STRUCTURE_TOOL,
                "search_codebase" to PromptTypeEnum.PROJECT_EXPLORE_STRUCTURE_TOOL,
                "exploreProject" to PromptTypeEnum.PROJECT_EXPLORE_STRUCTURE_TOOL,
                "explore_project" to PromptTypeEnum.PROJECT_EXPLORE_STRUCTURE_TOOL,
                "analyzeCode" to PromptTypeEnum.CODE_ANALYZE_TOOL,
                "analyze_code" to PromptTypeEnum.CODE_ANALYZE_TOOL,
                "modifyCode" to PromptTypeEnum.CODE_MODIFY_TOOL,
                "modify_code" to PromptTypeEnum.CODE_MODIFY_TOOL,
                "searchKnowledge" to PromptTypeEnum.KNOWLEDGE_SEARCH_TOOL,
                "search_knowledge" to PromptTypeEnum.KNOWLEDGE_SEARCH_TOOL,
                "knowledgeSearch" to PromptTypeEnum.KNOWLEDGE_SEARCH_TOOL,
                "executeCommand" to PromptTypeEnum.SYSTEM_EXECUTE_COMMAND_TOOL,
                "execute_command" to PromptTypeEnum.SYSTEM_EXECUTE_COMMAND_TOOL,
                "systemCommand" to PromptTypeEnum.SYSTEM_EXECUTE_COMMAND_TOOL,
                "system_command" to PromptTypeEnum.SYSTEM_EXECUTE_COMMAND_TOOL,
            )

        val corrected = corrections[invalidToolName]
        if (corrected != null) {
            logger.warn { "[PLANNER_CORRECTION] Corrected invalid tool name '$invalidToolName' to '${corrected.name}'" }
        }
        return corrected
    }

    private fun buildPlanContextSummary(plan: Plan): String {
        val totalSteps = plan.steps.size
        val completedSteps = plan.steps.count { it.status == StepStatusEnum.DONE }
        val failedSteps = plan.steps.count { it.status == StepStatusEnum.FAILED }
        val pendingSteps = plan.steps.count { it.status == StepStatusEnum.PENDING }

        val contextSummary =
            buildString {
                append("PLAN_CONTEXT: id=${plan.id} progress=$completedSteps/$totalSteps")
                if (failedSteps > 0) append(" failed=$failedSteps")
                if (pendingSteps > 0) append(" pending=$pendingSteps")
                appendLine()

                if (completedSteps > 0) {
                    appendLine("\nCOMPLETED_STEPS:")
                    plan.steps
                        .filter { it.status == StepStatusEnum.DONE }
                        .forEachIndexed { index, step ->
                            appendLine("\n[${index + 1}] ${step.stepToolName}: ${step.stepInstruction}")
                            step.toolResult?.let { result ->
                                appendLine(result.output)
                            }
                        }
                }

                if (failedSteps > 0) {
                    appendLine("\nFAILED_STEPS:")
                    plan.steps
                        .filter { it.status == StepStatusEnum.FAILED }
                        .forEach { step ->
                            appendLine("- ${step.stepToolName}: ${step.stepInstruction}")
                            step.toolResult?.let { result ->
                                appendLine("  ERROR: ${result.output.take(500)}")
                            }
                        }
                }

                if (pendingSteps > 0) {
                    appendLine("\nPENDING_STEPS:")
                    plan.steps
                        .filter { it.status == StepStatusEnum.PENDING }
                        .forEach { step ->
                            appendLine("- ${step.stepToolName}: ${step.stepInstruction}")
                        }
                }
            }

        return contextSummary
    }

    private fun buildStepsContext(plan: Plan): Map<String, String> =
        mapOf(
            "userRequest" to plan.englishInstruction,
            "projectDescription" to (plan.projectDocument?.description ?: "Project: ${plan.projectDocument?.name}"),
            "completedSteps" to
                plan.steps
                    .filter { it.status == StepStatusEnum.DONE }
                    .joinToString("\n") { "${it.stepToolName}:${it.toolResult}" },
            "totalSteps" to plan.steps.size.toString(),
            "questionChecklist" to plan.questionChecklist.joinToString(", "),
            "availableTools" to availableTools,
            "toolDescriptions" to toolDescriptions,
            // Add missing placeholders for PLANNING_CREATE_PLAN userPrompt
            "clientDescription" to (
                plan.clientDocument.description
                    ?: "Client: ${plan.clientDocument.name}"
            ),
            "previousConversations" to "", // Empty for now - could be enhanced later
            "planHistory" to "", // Empty for now - could be enhanced later
            "planContext" to buildPlanContextSummary(plan),
            "initialRagQueries" to plan.initialRagQueries.joinToString(", "),
            "knowledgeSearchToolName" to PromptTypeEnum.KNOWLEDGE_SEARCH_TOOL.aliases.first(),
            "analysisReasoningToolName" to PromptTypeEnum.ANALYSIS_REASONING_TOOL.aliases.first(),
        )
}
