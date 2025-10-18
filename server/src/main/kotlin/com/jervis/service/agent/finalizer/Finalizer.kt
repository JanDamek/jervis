package com.jervis.service.agent.finalizer

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStatus
import com.jervis.domain.plan.StepStatus
import com.jervis.dto.ChatResponse
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.dto.LlmResponseWrapper
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Finalizer produces the final user-facing response based on the task context.
 * It goes through all context plans that are COMPLETED or FAILED and finalizes them
 * with an LLM call. The LLM result is stored in the plan as a finalAnswer and
 * the plan is marked as FINALIZED. The response aggregates summaries of all processed plans.
 */
@Service
class Finalizer(
    private val gateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun finalize(context: TaskContext): ChatResponse {
        logger.debug { "FINALIZER_START: Processing context=${context.id} with ${context.plans.size} plans" }

        val finalizedPlans =
            context.plans
                .filter { it.status == PlanStatus.COMPLETED || it.status == PlanStatus.FAILED }
                .map { plan ->
                    logger.debug {
                        "FINALIZER_PLAN: Processing planId=${plan.id}, status=${plan.status}, originalQuestion='${plan.originalQuestion}'"
                    }
                    logger.debug { "FINALIZER_PLAN_CONTEXT: contextSummary='${plan.contextSummary}', finalAnswer='${plan.finalAnswer}'" }

                    val userLang = plan.originalLanguage.ifBlank { "EN" }
                    val mappingValues = buildFinalizerContext(context, plan, userLang)

                    logger.debug { "FINALIZER_MAPPING_VALUES: $mappingValues" }

                    val answer =
                        gateway
                            .callLlm(
                                type = PromptTypeEnum.FINALIZER_ANSWER,
                                responseSchema = LlmResponseWrapper(),
                                quick = context.quick,
                                mappingValue = mappingValues,
                                outputLanguage = userLang,
                            )
                    plan.finalAnswer = answer.result.response
                    plan.status = PlanStatus.FINALIZED
                    plan.updatedAt = Instant.now()
                    plan
                }

        val planAnswers =
            finalizedPlans
                .joinToString(separator = "\n\n") { plan ->
                    val title = plan.originalQuestion.ifBlank { plan.englishQuestion }
                    buildList {
                        title.takeIf { it.isNotBlank() }?.let { add("Question: $it") }
                        plan.finalAnswer?.let { add("Answer: $it") }
                    }.joinToString("\n")
                }.ifBlank { "No plans were finalized." }

        // Include missing requirements if checklist validation shows incomplete coverage
        val aggregatedMessage =
            buildString {
                append(planAnswers)
            }

        return ChatResponse(
            message = aggregatedMessage,
        )
    }

    private fun buildFinalizerContext(
        context: TaskContext,
        plan: Plan,
        userLanguage: String,
    ): Map<String, String> {
        val planContextSummary = buildPlanContextSummary(plan)

        return mapOf(
            "userRequest" to plan.englishQuestion,
            "projectDescription" to (context.projectDocument.description ?: "Project: ${context.projectDocument.name}"),
            "clientDescription" to (context.clientDocument.description ?: "Client: ${context.clientDocument.name}"),
            "questionChecklist" to plan.questionChecklist.joinToString(", "),
            "initialRagQueries" to plan.initialRagQueries.joinToString(", "),
            "planContext" to planContextSummary,
            "completedSteps" to
                plan.steps
                    .filter { it.status == StepStatus.DONE }
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
        val completedSteps = plan.steps.count { it.status == StepStatus.DONE }
        val failedSteps = plan.steps.count { it.status == StepStatus.FAILED }

        return buildString {
            appendLine("Plan ID: ${plan.id}")
            appendLine("Original Question: ${plan.originalQuestion}")
            appendLine("English Question: ${plan.englishQuestion}")
            appendLine("Progress: $completedSteps/$totalSteps steps completed")
            if (failedSteps > 0) {
                appendLine("Failed Steps: $failedSteps")
            }

            if (completedSteps > 0) {
                appendLine("\nCompleted Steps:")
                plan.steps
                    .filter { it.status == StepStatus.DONE }
                    .forEachIndexed { index, step ->
                        appendLine("${index + 1}. ${step.stepToolName}: ${step.stepInstruction}")
                        step.toolResult?.let { result ->
                            appendLine("   Result: ${result.output.take(200)}")
                        }
                    }
            }

            if (failedSteps > 0) {
                appendLine("\nFailed Steps:")
                plan.steps
                    .filter { it.status == StepStatus.FAILED }
                    .forEach { step ->
                        appendLine("- ${step.stepToolName}: ${step.stepInstruction}")
                        step.toolResult?.let { result ->
                            appendLine("   Error: ${result.output.take(200)}")
                        }
                    }
            }
        }
    }
}
