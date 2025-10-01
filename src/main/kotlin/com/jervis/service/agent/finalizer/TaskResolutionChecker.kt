package com.jervis.service.agent.finalizer

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.domain.ToolResult
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
                responseSchema = LlmAnalysisResult(),
                quick = false,
                mappingValue = mapOf("contextData" to contextData),
            )

        logger.info {
            "LLM analysis complete for ${taskContext.name}: " +
                "complete=${analysisResult.complete}"
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

            // Include questionChecklist for validation
            if (plan.questionChecklist.isNotEmpty()) {
                contextInfo.append("Question Checklist (MUST be verified as completed):\n")
                plan.questionChecklist.forEachIndexed { index, checklistItem ->
                    contextInfo.append("  ${index + 1}. $checklistItem\n")
                }
            }

            contextInfo.append("Steps (${plan.steps.size}):\n")

            plan.steps.forEach { step ->
                contextInfo.append("  - Step ${step.order}: ${step.stepToolName}\n")
                contextInfo.append("    Description: ${step.stepInstruction}\n")
                contextInfo.append("    Status: ${step.status}\n")

                // Include actual step content for better model understanding
                when (val output = step.toolResult) {
                    is ToolResult.Ok -> {
                        contextInfo.append(
                            "    Output: SUCCESS - ${
                                output.output
                                    .lineSequence()
                                    .firstOrNull()
                                    ?.take(300) ?: ""
                            }\n",
                        )
                    }

                    is ToolResult.Error -> {
                        val errorMsg = output.errorMessage ?: "Unknown error"
                        contextInfo.append("    Output: FAILED - $errorMsg\n")
                        if (output.output.isNotBlank()) {
                            contextInfo.append("    Error Details: ${output.output.take(500)}\n")
                        }
                    }

                    is ToolResult.Ask -> {
                        contextInfo.append(
                            "    Output: ASK - ${
                                output.output
                                    .lineSequence()
                                    .firstOrNull()
                                    ?.take(300) ?: ""
                            }\n",
                        )
                    }

                    is ToolResult.Stop -> {
                        contextInfo.append("    Output: STOPPED - ${output.reason}\n")
                        if (output.output.isNotBlank()) {
                            contextInfo.append("    Stop Details: ${output.output.take(300)}\n")
                        }
                    }

                    is ToolResult.InsertStep -> {
                        contextInfo.append("    Output: INSERT_STEP - ${output.stepToInsert.stepToolName}\n")
                        if (output.output.isNotBlank()) {
                            contextInfo.append("    Insert Details: ${output.output}\n")
                        }
                    }

                    null -> {
                        contextInfo.append("    Output: None\n")
                    }
                }
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
        val missingRequirements: List<String> = emptyList(),
        val qualityIssues: List<String> = emptyList(),
        val recommendations: List<String> = emptyList(),
    )
}
