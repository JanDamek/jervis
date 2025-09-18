package com.jervis.service.agent.planner

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatus
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.domain.ToolResult
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Planner creates a plan for a given task context.
 * Prefers LLM-driven plan, falls back to deterministic default when needed.
 */
@Service
class Planner(
    private val llmGateway: LlmGateway,
) {
    var allToolDescriptions: String = ""
    private val logger = KotlinLogging.logger {}

    @Serializable
    private data class PlannerStepDto(
        val tool: String = "",
        val taskDescription: String? = null,
        val stepBack: Int = 0,
    )

    suspend fun createPlan(
        context: TaskContext,
        plan: Plan,
    ): Plan {
        val mappingValue =
            mapOf(
                "toolDescriptions" to allToolDescriptions,
                "primaryUserGoal" to plan.englishQuestion,
                "clientDescription" to (context.clientDocument.description ?: ""),
                "projectDescription" to (context.projectDocument.description ?: ""),
                "previousConversationContext" to buildPreviousConversationContext(context, plan),
                "existingPlanHistory" to buildExistingPlanHistory(plan),
                "plannerInstruction" to buildPlannerInstruction(plan),
            )

        val llmAnswer =
            llmGateway.callLlm(
                type = PromptTypeEnum.PLANNER,
                userPrompt = "",
                quick = context.quick,
                mappingValue = mappingValue,
                responseSchema = listOf(PlannerStepDto()),
            )

        val stepsList =
            parseAnswerToSteps(llmAnswer, plan.id, context.id)

        logger.info { "PLANNER_LLM_PLAN_BUILT: steps=${stepsList.size}" }
        logger.debug { "PLANNER_FINAL_STEPS: ${stepsList.map { "Step ${it.order}: ${it.name} - ${it.taskDescription}" }}" }

        return plan.apply { this.steps = stepsList }
    }

    private fun buildPreviousConversationContext(
        context: TaskContext,
        plan: Plan,
    ): String {
        val otherPlans = context.plans.filter { it.id != plan.id }
        if (otherPlans.isEmpty()) return ""

        return buildString {
            appendLine("PREVIOUS CONVERSATION CONTEXT:")
            appendLine("This context has previous conversations that provide important background:")

            otherPlans.sortedBy { it.createdAt }.forEach { previousPlan ->
                appendLine()
                appendLine("Previous Question: ${previousPlan.originalQuestion}")
                if (previousPlan.englishQuestion.isNotBlank() && previousPlan.englishQuestion != previousPlan.originalQuestion) {
                    appendLine("English Translation: ${previousPlan.englishQuestion}")
                }

                if (previousPlan.contextSummary?.isNotBlank() == true) {
                    appendLine("Plan Summary: ${previousPlan.contextSummary}")
                }

                // Show key completed steps from the previous plan
                val completedSteps = previousPlan.steps.filter { it.status == StepStatus.DONE }
                if (completedSteps.isNotEmpty()) {
                    appendLine("Key completed actions:")
                    completedSteps.forEach { step ->
                        step.output?.output?.let {
                            appendLine("  - ${step.name}: $it")
                        }
                    }
                }
            }

            appendLine()
            appendLine("Use this previous context to inform your planning for the current question.")
        }
    }

    private fun buildExistingPlanHistory(plan: Plan): String {
        if (plan.steps.isEmpty()) return ""

        return buildString {
            appendLine("EXISTING PLAN HISTORY:")
            appendLine("Steps so far:")

            plan.steps.sortedBy { it.order }.forEach { step ->
                when (step.status) {
                    StepStatus.DONE -> {
                        val output = step.output?.output ?: "No output"
                        appendLine("- Step ${step.order}: ${step.name} → DONE")
                        appendLine("  Output: ${output.lines().firstOrNull()?.take(200) ?: "Empty"}")
                    }

                    StepStatus.FAILED -> {
                        val errorOutput = step.output as? ToolResult.Error
                        val errorMessage = errorOutput?.errorMessage ?: "Unknown error"
                        val fullOutput = step.output?.output ?: "No output"
                        appendLine("- Step ${step.order}: ${step.name} → FAILED")
                        appendLine("  Error: $errorMessage")
                        appendLine("  Output: ${fullOutput.lines().firstOrNull()?.take(200) ?: "Empty"}")
                    }

                    StepStatus.PENDING -> {
                        appendLine("- Step ${step.order}: ${step.name} → PENDING")
                        appendLine("  Task: ${step.taskDescription}")
                    }
                }
            }

            if (plan.contextSummary?.isNotBlank() == true) {
                appendLine()
                appendLine("CONTEXT SUMMARY (from previous steps):")
                appendLine(plan.contextSummary)
            }
        }
    }

    private fun buildPlannerInstruction(plan: Plan): String =
        buildString {
            appendLine("PLANNER INSTRUCTION:")
            if (plan.steps.isNotEmpty()) {
                val failedSteps = plan.steps.filter { it.status == StepStatus.FAILED }
                val lastFailedStep = failedSteps.maxByOrNull { it.order }

                if (lastFailedStep != null) {
                    appendLine("The previous plan failed at Step ${lastFailedStep.order} (${lastFailedStep.name}).")
                    appendLine("You MUST create a NEW plan that:")
                    appendLine("1. Keeps all completed steps unchanged.")
                    appendLine("2. Replaces the failed step ${lastFailedStep.order} and ALL following steps.")
                    appendLine("3. Uses different strategies/tools from that point onward.")
                } else {
                    appendLine("The current plan is incomplete or partially failed.")
                    appendLine("Please create a NEW full plan that:")
                    appendLine("1. Preserves all completed steps.")
                    appendLine("2. Fixes or replaces failed steps using alternative strategies.")
                    appendLine("3. Completes the original user goal: '${plan.englishQuestion}'")
                }
            } else {
                appendLine("Please create a NEW plan from scratch to fulfill the user's goal above.")
                appendLine("Split the task into a minimal sequence of steps using available tools.")
            }
        }

    private fun parseAnswerToSteps(
        inputSteps: List<PlannerStepDto>,
        planId: ObjectId,
        contextId: ObjectId,
    ): List<PlanStep> {
        logger.debug { "PLANNER_PARSE_STEPS: Processing ${inputSteps.size} steps" }

        require(inputSteps.isNotEmpty()) {
            logger.error { "PLANNER_NO_STEPS: Parsed DTO list was empty" }
            "Planner expected a non-empty JSON array of steps."
        }

        val steps =
            inputSteps.mapIndexed { idx, dto ->
                require(dto.tool.isNotBlank()) {
                    logger.error { "PLANNER_INVALID_STEP: Step at index $idx has empty tool: $dto" }
                    "Invalid step at index $idx: 'tool' must be non-empty."
                }
                PlanStep(
                    id = ObjectId.get(),
                    name = dto.tool.trim(),
                    taskDescription = dto.taskDescription ?: "",
                    stepBack = dto.stepBack,
                    order = idx + 1,
                    planId = planId,
                    contextId = contextId,
                    status = StepStatus.PENDING,
                    output = null,
                )
            }

        logger.debug { "PLANNER_CREATED_STEPS: Created ${steps.size} PlanSteps with PENDING status" }
        return steps
    }
}
