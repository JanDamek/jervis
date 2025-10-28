package com.jervis.service.agent.finalizer

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStatusEnum
import com.jervis.domain.plan.StepStatusEnum
import com.jervis.dto.ChatResponse
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.dto.LlmResponseWrapper
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Finalizer produces the final user-facing response based on a plan.
 * It processes a COMPLETED or FAILED plan and finalizes it with an LLM call.
 * The LLM result is stored in the plan as a finalAnswer and the plan is marked as FINALIZED.
 */
@Service
class Finalizer(
    private val gateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun finalize(plan: Plan): ChatResponse {
        logger.debug {
            "FINALIZER_START: Processing planId=${plan.id}, status=${plan.status}, taskInstruction='${plan.taskInstruction}'"
        }
        logger.debug { "FINALIZER_PLAN_CONTEXT: contextSummary='${plan.contextSummary}', finalAnswer='${plan.finalAnswer}'" }

        // If plan already has a final answer, use it; otherwise generate one via LLM
        val finalAnswer =
            if (plan.finalAnswer != null) {
                logger.debug { "FINALIZER_EXISTING_ANSWER: Using existing finalAnswer from plan" }
                plan.finalAnswer!!
            } else {
                val userLang = plan.originalLanguage.ifBlank { "EN" }
                val mappingValues = buildFinalizerContext(plan, userLang)

                logger.debug { "FINALIZER_MAPPING_VALUES: $mappingValues" }

                val answer =
                    gateway
                        .callLlm(
                            type = PromptTypeEnum.FINALIZER_ANSWER,
                            responseSchema = LlmResponseWrapper(),
                            quick = plan.quick,
                            mappingValue = mappingValues,
                            outputLanguage = userLang,
                            backgroundMode = plan.backgroundMode,
                        )

                plan.finalAnswer = answer.result.response
                answer.result.response
            }

        plan.status = PlanStatusEnum.FINALIZED

        val title = plan.taskInstruction.ifBlank { plan.englishInstruction }
        val responseMessage =
            buildList {
                title.takeIf { it.isNotBlank() }?.let { add("Question: $it") }
                add("Answer: $finalAnswer")
        }.joinToString("\n")

        return ChatResponse(
            message = responseMessage,
        )
    }

    private fun buildFinalizerContext(
        plan: Plan,
        userLanguage: String,
    ): Map<String, String> {
        val planContextSummary = buildPlanContextSummary(plan)

        return mapOf(
            "userRequest" to plan.englishInstruction,
            "projectDescription" to (
                plan.projectDocument?.description
                    ?: "Project: ${plan.projectDocument?.name}"
            ),
            "clientDescription" to (plan.clientDocument.description ?: "Client: ${plan.clientDocument.name}"),
            "questionChecklist" to plan.questionChecklist.joinToString(", "),
            "initialRagQueries" to plan.initialRagQueries.joinToString(", "),
            "planContext" to planContextSummary,
            "completedSteps" to
                plan.steps
                    .filter { it.status == StepStatusEnum.DONE }
                    .joinToString("\n") { step ->
                        buildString {
                            append("${step.stepToolName}: ${step.stepInstruction}")
                            step.toolResult?.let { result ->
                                append("\n  Result: ${result.output.take(500)}")
                            }
                        }
                    },
            "userLanguage" to userLanguage,
        )
    }

    private fun buildPlanContextSummary(plan: Plan): String {
        val totalSteps = plan.steps.size
        val completedSteps = plan.steps.count { it.status == StepStatusEnum.DONE }
        val failedSteps = plan.steps.count { it.status == StepStatusEnum.FAILED }

        return buildString {
            append("PLAN_SUMMARY: id=${plan.id} progress=$completedSteps/$totalSteps")
            if (failedSteps > 0) append(" failed=$failedSteps")
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
        }
    }
}
