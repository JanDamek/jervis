package com.jervis.service.agent.finalizer

import com.jervis.configuration.prompts.McpToolType
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.PlanStatus
import com.jervis.dto.ChatResponse
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.prompts.PromptRepository
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
    private val promptRepository: PromptRepository,
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

                    val userLang = plan.originalLanguage.ifBlank { "en" }
                    val userPrompt =
                        buildUserPrompt(
                            originalQuestion = plan.originalQuestion,
                            englishQuestion = plan.englishQuestion,
                            contextSummary = plan.contextSummary,
                            finalAnswer = plan.finalAnswer,
                            userLanguage = userLang,
                        )

                    logger.debug { "FINALIZER_USER_PROMPT: userPrompt='$userPrompt'" }

                    val systemPrompt = promptRepository.getSystemPrompt(McpToolType.FINALIZER)
                    val modelParams = promptRepository.getEffectiveModelParams(McpToolType.FINALIZER)

                    val answer =
                        runCatching {
                            gateway
                                .callLlm(
                                    type = ModelType.INTERNAL,
                                    userPrompt = userPrompt,
                                    systemPrompt = systemPrompt,
                                    outputLanguage = userLang,
                                    quick = context.quick,
                                    modelParams = modelParams,
                                ).answer
                                .trim()
                        }.getOrElse {
                            logger.warn(it) { "FINALIZER_LLM_FAIL: Falling back to summary for plan=${'$'}{plan.id}" }
                            (
                                plan.finalAnswer ?: plan.contextSummary
                                    ?: "No output available"
                            ).trim()
                        }.ifBlank { plan.contextSummary ?: "No output available" }

                    plan.finalAnswer = answer
                    plan.status = PlanStatus.FINALIZED
                    plan.updatedAt = Instant.now()
                    plan
                }

        val aggregatedMessage =
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

        return ChatResponse(
            message = aggregatedMessage,
        )
    }

    private fun buildSystemPrompt(): String =
        """
        You are a Finalizer assistant. Produce one clear and unambiguous answer for the user based on the provided plan context.
        
        CRITICAL REQUIREMENT: You must NEVER invent or fabricate any information. All information you provide must come from available tools (McpTools) or the actual plan context provided to you. If you don't have access to specific information through tools, explicitly state that you cannot provide it rather than guessing or inventing details.
        
        - Be concise and actionable.
        - If a direct answer is possible, state it immediately.
        - Summarize only what is necessary for the user to act.
        - Do not mention internal tools, steps, or planning.
        """.trimIndent()

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
