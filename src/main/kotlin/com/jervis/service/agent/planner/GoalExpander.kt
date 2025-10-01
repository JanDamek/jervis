package com.jervis.service.agent.planner

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.GoalDto
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import com.jervis.domain.plan.StepStatus
import com.jervis.service.gateway.core.LlmGateway
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Enhanced GoalExpander implementing StepExpander interface.
 * Uses functional approach with proper error handling and configuration.
 */
@Service
class GoalExpander(
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    // Tool registry compatibility properties - populated by McpToolRegistry
    var allToolDescriptions: String = ""
    var availableToolNames: String = ""

    /**
     * Expands a goal into executable plan steps using LLM and proper error handling.
     * Implements StepExpander interface with functional Result pattern.
     */
    suspend fun expandGoal(
        context: TaskContext,
        plan: Plan,
        goal: GoalDto,
        discoveryResult: String,
    ): List<PlanStep> {
        val promptContext = buildExpandContext(context, plan, goal, discoveryResult)

        val result =
            llmGateway.callLlm(
                type = PromptTypeEnum.PLANNING_CREATE_PLAN,
                quick = context.quick,
                responseSchema = Planner.StepsDto(),
                mappingValue = promptContext,
            )

        val steps =
            result.steps.map { dto ->
                createPlanStepFromDto(dto, plan.id, context.id, goal.goalId)
            }

        logger.info { "Goal expanded successfully: ${goal.goalId} -> ${steps.size} steps" }
        return steps
    }

    /**
     * Creates a PlanStep from LLM response DTO with proper field mapping.
     * Extracts step creation logic for better readability and testability.
     */
    private fun createPlanStepFromDto(
        dto: Planner.StepDto,
        planId: ObjectId,
        contextId: ObjectId,
        goalId: Int,
    ): PlanStep =
        PlanStep(
            id = ObjectId(),
            planId = planId,
            contextId = contextId,
            order = -1,
            stepToolName = dto.stepToolName,
            stepInstruction = dto.stepInstruction,
            stepDependsOn = listOf(dto.stepDependsOn).filter { it >= 0 },
            stepGroup = "goal-$goalId",
            status = StepStatus.PENDING,
        )

    private fun buildExpandContext(
        ctx: TaskContext,
        plan: Plan,
        goal: GoalDto,
        discoveryResult: String,
    ): Map<String, String> {
        // Build simple planContext with goal info and discovery result
        val planContext =
            "GOAL TO EXPAND:\n" +
                "Goal ID: ${goal.goalId}\n" +
                "Goal Intent: ${goal.goalIntent}\n" +
                (if (goal.dependsOn.isNotEmpty()) "Depends On Goals: ${goal.dependsOn.joinToString(", ")}\n" else "") +
                "\nDISCOVERY CONTEXT:\n" +
                discoveryResult

        // Build simple question checklist
        val questionChecklist =
            "- How should the goal be broken down into specific steps?\n" +
                "- Which tools are needed to accomplish: ${goal.goalIntent}?\n" +
                "- What are the dependencies between steps within this goal?\n" +
                (
                    if (goal.dependsOn.isNotEmpty()) {
                        "- How does this goal relate to dependencies: ${
                            goal.dependsOn.joinToString(
                                ", ",
                            )
                        }?\n"
                    } else {
                        ""
                    }
                )

        // Build simple investigation guidance
        val investigationGuidance =
            "- Each step must use exactly ONE tool from available tools\n" +
                "- If file paths are unknown, add discovery steps first\n" +
                "- Use stepDependsOn for ordering within this goal (0-based indices)\n" +
                "- stepTitle should be short and descriptive\n" +
                "- stepInstruction should be clear and actionable\n" +
                "- IMPORTANT: When scheduling future actions, use SYSTEM_SCHEDULE_TASK with the complete task in taskInstruction. Do NOT create separate steps for scheduled tasks.\n" +
                "- SYSTEM_SCHEDULE_TASK can schedule ANY type of work including emails, calls, meetings, reminders, etc."

        return mapOf(
            "clientDescription" to "Development team working on ${ctx.projectDocument.name}",
            "projectDescription" to (ctx.projectDocument.description ?: "Project: ${ctx.projectDocument.name}"),
            "previousConversations" to "", // Empty for goal expansion
            "planHistory" to "", // Empty for goal expansion
            "planContext" to planContext.trim(),
            "userRequest" to plan.englishQuestion,
            "questionChecklist" to questionChecklist.trim(),
            "investigationGuidance" to investigationGuidance.trim(),
            "availableTools" to availableToolNames,
            "toolDescriptions" to allToolDescriptions,
        )
    }
}
