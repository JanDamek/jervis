package com.jervis.service.agent.finalizer

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.PlanStatus
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
    private val taskResolutionChecker: TaskResolutionChecker,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun finalize(context: TaskContext): ChatResponse {
        logger.debug { "FINALIZER_START: Processing context=${context.id} with ${context.plans.size} plans" }

        // Validate questionChecklist coverage using TaskResolutionChecker
        val resolutionResult = taskResolutionChecker.performLlmAnalysis(context)
        logger.debug {
            "FINALIZER_CHECKLIST_VALIDATION: complete=${resolutionResult.complete}, " +
                "missingRequirements=${resolutionResult.missingRequirements.size}"
        }

        val finalizedPlans =
            context.plans
                .filter { it.status == PlanStatus.COMPLETED || it.status == PlanStatus.FAILED }
                .map { plan ->
                    logger.debug {
                        "FINALIZER_PLAN: Processing planId=${plan.id}, status=${plan.status}, originalQuestion='${plan.originalQuestion}'"
                    }
                    logger.debug { "FINALIZER_PLAN_CONTEXT: contextSummary='${plan.contextSummary}', finalAnswer='${plan.finalAnswer}'" }

                    val userLang = plan.originalLanguage.ifBlank { "EN" }
                    val userPrompt =
                        buildUserPrompt(
                            originalQuestion = plan.originalQuestion,
                            englishQuestion = plan.englishQuestion,
                            contextSummary = plan.contextSummary,
                            finalAnswer = plan.finalAnswer,
                            userLanguage = userLang,
                        )

                    logger.debug { "FINALIZER_USER_PROMPT: userPrompt='$userPrompt'" }

                    val answer =
                        gateway
                            .callLlm(
                                type = PromptTypeEnum.FINALIZER_ANSWER,
                                responseSchema = LlmResponseWrapper(),
                                quick = context.quick,
                                mappingValue = mapOf("promptData" to userPrompt),
                                outputLanguage = userLang,
                            )
                    plan.finalAnswer = answer.response
                    plan.status = PlanStatus.FINALIZED
                    plan.updatedAt = Instant.now()
                    plan
                }

        val planAnswers =
            finalizedPlans
                .filter { it.status == PlanStatus.FINALIZED }
                .toList()
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

                if (!resolutionResult.complete && resolutionResult.missingRequirements.isNotEmpty()) {
                    append("\n\n--- INCOMPLETE CHECKLIST COVERAGE ---\n")
                    append("The following requirements from the original question checklist need additional attention:\n")
                    resolutionResult.missingRequirements.forEachIndexed { index, requirement ->
                        append("${index + 1}. $requirement\n")
                    }
                    if (resolutionResult.recommendations.isNotEmpty()) {
                        append("\nRecommendations:\n")
                        resolutionResult.recommendations.forEach { recommendation ->
                            append("• $recommendation\n")
                        }
                    }
                }
            }

        return ChatResponse(
            message = aggregatedMessage,
        )
    }

    private fun buildUserPrompt(
        originalQuestion: String,
        englishQuestion: String,
        contextSummary: String?,
        finalAnswer: String?,
        userLanguage: String,
    ): String =
        buildString {
            appendLine("User language (ISO-639-1): $userLanguage")
            appendLine("Answer strictly in this language.")
            appendLine()
            appendLine("Context:")
            appendLine("originalQuestion=$originalQuestion")
            appendLine("englishQuestion=$englishQuestion")
            if (!contextSummary.isNullOrBlank()) appendLine("summary=$contextSummary")
            if (!finalAnswer.isNullOrBlank()) appendLine("answer=$finalAnswer")
            appendLine()
            appendLine("Write the final answer for the user.")
        }
}
