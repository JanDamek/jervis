package com.jervis.service.agent.planner

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatusEnum
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.prompts.ToolSelectorService
import com.jervis.service.prompts.ToolRequirement
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
    private val toolSelectorService: ToolSelectorService,
) {
    private val logger = KotlinLogging.logger {}

    @Serializable
    data class StepPlanDto(
        val stepInstruction: String = "",
        val stepDependsOn: Int = -1,
    )

    @Serializable
    data class PlannerResponseDto(
        val nextSteps: List<StepPlanDto> = emptyList(),
        val tool_requirements: List<ToolRequirement> = emptyList(),
    )

    /**
     * Suggests next steps based on current plan progress.
     * Uses iterative approach instead of complex goal-based planning.
     */
    suspend fun suggestNextSteps(plan: Plan): List<PlanStep> {
        val totalSteps = plan.steps.size
        val completedSteps = plan.steps.count { it.status == StepStatusEnum.DONE }
        logger.info { "PLANNER_START: planId=${plan.id} currentSteps=$totalSteps completed=$completedSteps" }

        val parsedResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.PLANNING_CREATE_PLAN_TOOL,
                quick = plan.quick,
                responseSchema = PlannerResponseDto(),
                mappingValue = buildStepsContext(plan),
                backgroundMode = plan.backgroundMode,
            )

        // Store think content if present
        parsedResponse.thinkContent?.let { thinkContent ->
            logger.info { "[PLANNER_THINK] Plan ${plan.id}: think content captured (${thinkContent.length} chars)" }
            plan.thinkingSequence += thinkContent
        }

        val plannerOut = parsedResponse.result
        // Audit: log declared tool requirements from planner
        if (plannerOut.tool_requirements.isNotEmpty()) {
            logger.info { "[PLANNER_AUDIT] tool_requirements=${plannerOut.tool_requirements.map { it.capability to it.detail }}" }
        } else {
            logger.info { "[PLANNER_AUDIT] tool_requirements=[]" }
        }

        val selectedTools = toolSelectorService.selectTools(plannerOut.tool_requirements)
        // Audit: log selected tools mapping
        if (selectedTools.isNotEmpty()) {
            logger.info { "[TOOL_SELECTOR_AUDIT] selectedTools=${selectedTools.map { it.tool.name to it.params }}" }
        } else {
            logger.info { "[TOOL_SELECTOR_AUDIT] selectedTools=[]" }
        }

        val newSteps =
            plannerOut.nextSteps.mapIndexed { index, dto ->
                val chosenTool =
                    selectedTools.getOrNull(index)?.tool
                        ?: selectedTools.firstOrNull()?.tool
                        ?: PromptTypeEnum.ANALYSIS_REASONING_TOOL
                createPlanStep(chosenTool, dto, plan.id, plan.steps.size + index + 1)
            }

        logger.info { "PLANNER_RESULT: planId=${plan.id} suggestedSteps=${newSteps.size} tools=${newSteps.map { it.stepToolName.name }}" }
        return newSteps
    }

    private fun createPlanStep(
        tool: PromptTypeEnum,
        dto: StepPlanDto,
        planId: ObjectId,
        order: Int,
    ): PlanStep =
        PlanStep(
            id = ObjectId(),
            order = order,
            stepToolName = tool,
            stepInstruction = dto.stepInstruction,
            stepDependsOn = dto.stepDependsOn,
            status = StepStatusEnum.PENDING,
        )

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
                                appendLine("  ERROR: ${result.output}")
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
