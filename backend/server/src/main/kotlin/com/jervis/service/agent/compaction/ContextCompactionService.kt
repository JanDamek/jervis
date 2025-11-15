package com.jervis.service.agent.compaction

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatusEnum
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.TokenEstimationService
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Context Compaction Service - Phase 3 of planning process.
 * Detects context overflow and consolidates plan steps to fit within model limits.
 * Only triggers when context exceeds the planner model's capacity.
 */
@Service
class ContextCompactionService(
    private val llmGateway: LlmGateway,
    private val tokenEstimationService: TokenEstimationService,
    private val modelsProperties: ModelsProperties,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        private const val MIN_STEPS_FOR_COMPACTION = 3
        private const val SAFETY_MARGIN = 0.9 // Use 90% of context limit
    }

    @Serializable
    data class CompactionRange(
        val fromStep: Int = 0,
        val toStep: Int = 0,
        val summary: String = "",
    )

    @Serializable
    data class CompactionResponse(
        val compactionRanges: List<CompactionRange> = emptyList(),
    )

    /**
     * Result of context compaction check
     */
    data class CompactionResult(
        val needsCompaction: Boolean,
        val cannotCompact: Boolean = false,
        val currentContextSize: Int = 0,
        val maxContextSize: Int = 0,
    )

    /**
     * Check if plan context exceeds model limits and compact if necessary.
     * Returns CompactionResult indicating:
     * - needsCompaction=false: Context is within limits, no action needed
     * - needsCompaction=true, cannotCompact=false: Compaction was performed, check again
     * - needsCompaction=true, cannotCompact=true: Context too large but ≤3 steps, cannot compact
     */
    suspend fun checkAndCompact(plan: Plan): CompactionResult {
        // Get planner model configuration
        val plannerModels = modelsProperties.models[ModelTypeEnum.PLANNER]
            ?: throw IllegalStateException("No planner models configured")

        val plannerModel = plannerModels.firstOrNull()
            ?: throw IllegalStateException("No planner model available")

        val maxContextTokens = ((plannerModel.contextLength ?: 16000) * SAFETY_MARGIN).toInt()

        // Estimate current context size
        val currentContextSize = estimatePlanContextSize(plan)

        if (currentContextSize <= maxContextTokens) {
            logger.debug { "[CONTEXT_CHECK] planId=${plan.id} currentSize=$currentContextSize maxSize=$maxContextTokens - OK" }
            return CompactionResult(
                needsCompaction = false,
                currentContextSize = currentContextSize,
                maxContextSize = maxContextTokens
            )
        }

        logger.warn { "[CONTEXT_OVERFLOW] planId=${plan.id} currentSize=$currentContextSize maxSize=$maxContextTokens - Compaction needed" }

        // Check if we can compact (need more than 3 steps)
        if (plan.steps.size <= MIN_STEPS_FOR_COMPACTION) {
            logger.error { "[CONTEXT_OVERFLOW_CANNOT_COMPACT] planId=${plan.id} only ${plan.steps.size} steps, cannot compact (need >${MIN_STEPS_FOR_COMPACTION})" }
            return CompactionResult(
                needsCompaction = true,
                cannotCompact = true,
                currentContextSize = currentContextSize,
                maxContextSize = maxContextTokens
            )
        }

        // Perform compaction
        performCompaction(plan, maxContextTokens)

        // Return that compaction was performed - caller should check again
        return CompactionResult(
            needsCompaction = true,
            cannotCompact = false,
            currentContextSize = estimatePlanContextSize(plan),
            maxContextSize = maxContextTokens
        )
    }

    private suspend fun performCompaction(plan: Plan, maxTokens: Int) {
        logger.info { "[COMPACTION_START] planId=${plan.id} steps=${plan.steps.size} maxTokens=$maxTokens" }

        // Build context for compaction LLM
        val stepsContext = plan.steps.mapIndexed { index, step ->
            buildString {
                append("Step $index: ${step.stepToolName.name}")
                append(" - ${step.status.name}")
                append(" - ${step.stepInstruction.take(200)}")
                if (step.toolResult != null) {
                    append("\n  Result: ${step.toolResult.output.take(500)}")
                }
            }
        }.joinToString("\n\n")

        val mappingValues = mapOf(
            "planContext" to buildPlanContext(plan),
            "stepsContext" to stepsContext,
            "maxTokens" to maxTokens.toString(),
            "currentStepCount" to plan.steps.size.toString(),
        )

        val parsedResponse = llmGateway.callLlm(
            type = PromptTypeEnum.CONTEXT_COMPACTION,
            responseSchema = CompactionResponse(),
            correlationId = plan.correlationId,
            quick = plan.quick,
            mappingValue = mappingValues,
            backgroundMode = plan.backgroundMode,
        )

        val compactionOut = parsedResponse.result

        if (compactionOut.compactionRanges.isEmpty()) {
            logger.warn { "[COMPACTION_NO_RANGES] planId=${plan.id} - No compaction ranges suggested" }
            return
        }

        // Apply compaction ranges
        logger.info { "[COMPACTION_APPLY] planId=${plan.id} ranges=${compactionOut.compactionRanges.size}" }

        // Sort ranges by fromStep in descending order to avoid index shifting issues
        val sortedRanges = compactionOut.compactionRanges.sortedByDescending { it.fromStep }

        var compactedCount = 0
        for (range in sortedRanges) {
            if (range.fromStep < 0 || range.toStep >= plan.steps.size || range.fromStep > range.toStep) {
                logger.warn { "[COMPACTION_INVALID_RANGE] planId=${plan.id} from=${range.fromStep} to=${range.toStep}" }
                continue
            }

            val stepsToRemove = range.toStep - range.fromStep + 1
            val consolidatedStep = PlanStep(
                id = ObjectId(),
                order = range.fromStep,
                stepToolName = PromptTypeEnum.CONSOLIDATE_STEPS_TOOL,
                stepInstruction = "CONSOLIDATED ($stepsToRemove steps): ${range.summary}",
                status = StepStatusEnum.DONE,
                toolResult = com.jervis.service.mcp.domain.ToolResult.success(
                    toolName = "CONTEXT_COMPACTION",
                    summary = "Consolidated $stepsToRemove steps",
                    content = range.summary
                )
            )

            // Replace range with consolidated step
            val newSteps = buildList {
                addAll(plan.steps.subList(0, range.fromStep))
                add(consolidatedStep)
                if (range.toStep + 1 < plan.steps.size) {
                    addAll(plan.steps.subList(range.toStep + 1, plan.steps.size))
                }
            }

            // Reorder steps
            plan.steps = newSteps.mapIndexed { index, step ->
                step.copy(order = index)
            }

            compactedCount += stepsToRemove
            logger.info { "[COMPACTION_RANGE_APPLIED] planId=${plan.id} from=${range.fromStep} to=${range.toStep} removed=$stepsToRemove" }
        }

        val newContextSize = estimatePlanContextSize(plan)
        logger.info { "[COMPACTION_COMPLETE] planId=${plan.id} oldSteps=${plan.steps.size + compactedCount} newSteps=${plan.steps.size} newSize=$newContextSize maxTokens=$maxTokens" }

        // Note: We don't throw here - the calling loop will check again via checkAndCompact
        // If context is still too large, another compaction round will be triggered
        if (newContextSize > maxTokens) {
            logger.warn { "[COMPACTION_INCOMPLETE] planId=${plan.id} newSize=$newContextSize still exceeds maxTokens=$maxTokens - will need another compaction round" }
        } else {
            logger.info { "[COMPACTION_SUCCESS] planId=${plan.id} newSize=$newContextSize is now within maxTokens=$maxTokens" }
        }
    }

    private fun estimatePlanContextSize(plan: Plan): Int {
        val contextText = buildString {
            appendLine("Task: ${plan.taskInstruction}")
            appendLine("English: ${plan.englishInstruction}")
            appendLine("Questions: ${plan.questionChecklist.joinToString(", ")}")
            appendLine()

            plan.steps.forEach { step ->
                appendLine("Step ${step.order}: ${step.stepToolName.name}")
                appendLine("Status: ${step.status.name}")
                appendLine("Instruction: ${step.stepInstruction}")
                step.toolResult?.let { result ->
                    appendLine("Result: ${result.output}")
                }
                appendLine()
            }
        }

        return tokenEstimationService.estimateTokens(contextText)
    }

    private fun buildPlanContext(plan: Plan): String {
        return buildString {
            appendLine("Plan Context:")
            appendLine("- Task: ${plan.taskInstruction}")
            appendLine("- Total Steps: ${plan.steps.size}")
            appendLine("- Completed: ${plan.steps.count { it.status == StepStatusEnum.DONE }}")
            appendLine("- Pending: ${plan.steps.count { it.status == StepStatusEnum.PENDING }}")
            appendLine("- Failed: ${plan.steps.count { it.status == StepStatusEnum.FAILED }}")
        }
    }
}
