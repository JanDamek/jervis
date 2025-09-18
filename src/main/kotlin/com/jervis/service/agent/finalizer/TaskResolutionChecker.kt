package com.jervis.service.agent.finalizer

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.service.gateway.core.LlmGateway
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Task Resolution Checker Service.
 *
 * This service analyzes TaskContext to determine if all tasks from the original requirements
 * have been completed successfully. It examines all plans and their steps to verify completion
 * and provides detailed feedback on missing requirements.
 *
 * @author damekjan
 * @version 1.0
 * @since 15.09.2025
 */
@Service
class TaskResolutionChecker(
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Uses LLM to perform semantic analysis of task context completion.
     *
     * @param taskContext The task context to analyze
     * @return LlmAnalysisResult containing semantic analysis insights
     */
    suspend fun performLlmAnalysis(taskContext: TaskContext): LlmAnalysisResult {
        logger.info { "Performing LLM analysis for task context: ${taskContext.name}" }

        val contextData = buildContextPrompt(taskContext)
        val analysisResult =
            llmGateway.callLlm(
                type = PromptTypeEnum.TASK_RESOLUTION_CHECKER,
                userPrompt = contextData,
                quick = false,
                LlmAnalysisResult(),
                mappingValue = emptyMap(),
            )

        logger.info {
            "LLM analysis complete for ${taskContext.name}: " +
                "complete=${analysisResult.complete}, " +
                "confidence=${analysisResult.completionConfidence}"
        }
        return analysisResult
    }

    private fun buildContextPrompt(taskContext: TaskContext): String {
        val contextInfo = StringBuilder()

        contextInfo.append("TASK CONTEXT ANALYSIS REQUEST\n\n")
        contextInfo.append("Context Name: ${taskContext.name}\n")
        contextInfo.append("Context Summary: ${taskContext.contextSummary}\n\n")

        taskContext.plans.forEach { plan ->
            contextInfo.append("PLAN: ${plan.englishQuestion}\n")
            contextInfo.append("Original Question: ${plan.originalQuestion}\n")
            contextInfo.append("Original Language: ${plan.originalLanguage}\n")
            contextInfo.append("Status: ${plan.status}\n")
            contextInfo.append("Context Summary: ${plan.contextSummary ?: "None"}\n")
            contextInfo.append("Final Answer: ${plan.finalAnswer ?: "None"}\n")
            contextInfo.append("Steps (${plan.steps.size}):\n")

            plan.steps.forEach { step ->
                contextInfo.append("  - Step ${step.order}: ${step.name}\n")
                contextInfo.append("    Description: ${step.taskDescription}\n")
                contextInfo.append("    Status: ${step.status}\n")
                contextInfo.append("    Output: ${step.output?.let { "Present" } ?: "None"}\n")
            }
            contextInfo.append("\n")
        }

        return contextInfo.toString()
    }

    /**
     * Data class representing the result of LLM analysis
     */
    @Serializable
    data class LlmAnalysisResult(
        val complete: Boolean = false,
        val completionConfidence: Double = 0.0,
        val missingRequirements: List<String> = emptyList(),
        val qualityIssues: List<String> = emptyList(),
        val recommendations: List<String> = emptyList(),
        val summary: String = "",
    )
}
