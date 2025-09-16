package com.jervis.service.agent.finalizer

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStatus
import com.jervis.domain.plan.StepStatus
import com.jervis.service.gateway.LlmGateway
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
     * Checks if the task context is resolved by analyzing all plans and steps.
     *
     * A task is considered resolved when:
     * - All plans have status COMPLETED or FINALIZED
     * - All steps within plans have status DONE
     * - All original requirements from englishQuestion are addressed
     * - LLM semantic analysis confirms completeness (when available)
     *
     * @param taskContext The task context to analyze
     * @return ResolutionResult containing completion status and details
     */
    suspend fun checkIfIsResolvDetailed(taskContext: TaskContext): ResolutionResult {
        logger.info { "Checking resolution for task context: ${taskContext.name}" }

        if (taskContext.plans.isEmpty()) {
            logger.warn { "No plans found in task context ${taskContext.name}" }
            return ResolutionResult(
                isComplete = false,
                missingItems = listOf("No plans have been created for this task"),
                completedPlans = 0,
                totalPlans = 0,
                completedSteps = 0,
                totalSteps = 0,
            )
        }

        val analysisResult = analyzePlansAndSteps(taskContext.plans)
        val missingRequirements = identifyMissingRequirements(taskContext.plans)

        // Perform LLM semantic analysis for additional insights
        val llmAnalysis = performLlmAnalysis(taskContext)

        val isComplete =
            analysisResult.allPlansComplete &&
                analysisResult.allStepsComplete &&
                missingRequirements.isEmpty() &&
                llmAnalysis.complete &&
                llmAnalysis.completionConfidence >= 0.7

        val missingItems = mutableListOf<String>()

        if (!analysisResult.allPlansComplete) {
            missingItems.addAll(analysisResult.incompletePlans)
        }

        if (!analysisResult.allStepsComplete) {
            missingItems.addAll(analysisResult.incompleteSteps)
        }

        missingItems.addAll(missingRequirements)

        // Add LLM analysis insights
        if (!llmAnalysis.complete) {
            missingItems.addAll(llmAnalysis.missingRequirements.map { "LLM Analysis: $it" })
        }
        if (llmAnalysis.qualityIssues.isNotEmpty()) {
            missingItems.addAll(llmAnalysis.qualityIssues.map { "Quality Issue: $it" })
        }
        if (llmAnalysis.completionConfidence < 0.7) {
            missingItems.add("LLM Analysis: Low completion confidence (${llmAnalysis.completionConfidence})")
        }

        logger.info {
            "Resolution check complete for ${taskContext.name}: " +
                "isComplete=$isComplete, " +
                "plans=${analysisResult.completedPlans}/${analysisResult.totalPlans}, " +
                "steps=${analysisResult.completedSteps}/${analysisResult.totalSteps}, " +
                "llmConfidence=${llmAnalysis.completionConfidence}, " +
                "llmComplete=${llmAnalysis.complete}"
        }

        return ResolutionResult(
            isComplete = isComplete,
            missingItems = missingItems,
            completedPlans = analysisResult.completedPlans,
            totalPlans = analysisResult.totalPlans,
            completedSteps = analysisResult.completedSteps,
            totalSteps = analysisResult.totalSteps,
        )
    }

    private fun analyzePlansAndSteps(plans: List<Plan>): PlanAnalysisResult {
        val completedPlans = plans.count { it.status in listOf(PlanStatus.COMPLETED, PlanStatus.FINALIZED) }
        val totalPlans = plans.size

        val allSteps = plans.flatMap { it.steps }
        val completedSteps = allSteps.count { it.status == StepStatus.DONE }
        val totalSteps = allSteps.size

        val incompletePlans =
            plans
                .filter { it.status !in listOf(PlanStatus.COMPLETED, PlanStatus.FINALIZED) }
                .map { "Plan '${it.englishQuestion}' is not completed (status: ${it.status})" }

        val incompleteSteps =
            allSteps
                .filter { it.status != StepStatus.DONE }
                .map { "Step '${it.name}' is not completed (status: ${it.status})" }

        return PlanAnalysisResult(
            allPlansComplete = completedPlans == totalPlans,
            allStepsComplete = completedSteps == totalSteps,
            completedPlans = completedPlans,
            totalPlans = totalPlans,
            completedSteps = completedSteps,
            totalSteps = totalSteps,
            incompletePlans = incompletePlans,
            incompleteSteps = incompleteSteps,
        )
    }

    private fun identifyMissingRequirements(plans: List<Plan>): List<String> {
        val missingRequirements = mutableListOf<String>()

        // Check if plans have final answers
        plans.forEach { plan ->
            if ((plan.status == PlanStatus.COMPLETED || plan.status == PlanStatus.FINALIZED) &&
                (plan.finalAnswer.isNullOrBlank())
            ) {
                missingRequirements.add("Plan '${plan.englishQuestion}' is marked complete but has no final answer")
            }
        }

        // Check for failed plans or steps that might indicate unresolved issues
        val failedPlans = plans.filter { it.status == PlanStatus.FAILED }
        failedPlans.forEach { plan ->
            missingRequirements.add("Plan '${plan.englishQuestion}' has failed and needs to be resolved")
        }

        val failedSteps = plans.flatMap { it.steps }.filter { it.status == StepStatus.FAILED }
        failedSteps.forEach { step ->
            missingRequirements.add("Step '${step.name}' has failed: ${step.taskDescription}")
        }

        return missingRequirements
    }

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

    /**
     * Data class representing the result of a task resolution check
     */
    data class ResolutionResult(
        val isComplete: Boolean,
        val missingItems: List<String>,
        val completedPlans: Int,
        val totalPlans: Int,
        val completedSteps: Int,
        val totalSteps: Int,
    )

    /**
     * Internal data class for plan analysis results
     */
    private data class PlanAnalysisResult(
        val allPlansComplete: Boolean,
        val allStepsComplete: Boolean,
        val completedPlans: Int,
        val totalPlans: Int,
        val completedSteps: Int,
        val totalSteps: Int,
        val incompletePlans: List<String>,
        val incompleteSteps: List<String>,
    )
}
