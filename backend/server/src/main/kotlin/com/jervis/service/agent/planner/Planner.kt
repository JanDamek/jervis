package com.jervis.service.agent.planner

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.StepStatusEnum
import com.jervis.service.gateway.core.LlmGateway
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Simplified iterative Planner that suggests next steps based on the current plan.
 * Phase 1: Planner decides WHAT information is needed (descriptive requirements only).
 * No tool selection - just pure requirements.
 */
@Service
class Planner(
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    @Serializable
    data class NextStepRequest(
        val description: String = "",
    )

    @Serializable
    data class PlannerResponseDto(
        val nextSteps: List<NextStepRequest> = emptyList(),
    )

    /**
     * Suggests next steps based on current plan progress.
     * Returns descriptive requirements without tool assignments.
     * Phase 1: Planner only decides WHAT information is needed.
     */
    suspend fun suggestNextSteps(plan: Plan): PlannerResponseDto {
        val totalSteps = plan.steps.size
        val completedSteps = plan.steps.count { it.status == StepStatusEnum.DONE }
        logger.info { "PLANNER_START: planId=${plan.id} currentSteps=$totalSteps completed=$completedSteps" }

        val parsedResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.PLANNING_CREATE_PLAN_TOOL,
                responseSchema = PlannerResponseDto(),
                correlationId = plan.correlationId,
                quick = plan.quick,
                mappingValue = buildStepsContext(plan),
                backgroundMode = plan.backgroundMode,
            )

        // Store think content if present
        parsedResponse.thinkContent?.let { thinkContent ->
            logger.info { "[PLANNER_THINK] Plan ${plan.id}: think content captured (${thinkContent.length} chars)" }
            plan.thinkingSequence += thinkContent
        }

        val plannerOut = parsedResponse.result
        logger.info { "[PLANNER_RESULT] planId=${plan.id} suggestedSteps=${plannerOut.nextSteps.size} descriptions=${plannerOut.nextSteps.map { it.description }}" }

        return plannerOut
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
