package com.jervis.service.agent.planner

import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStatus
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.execution.PlanExecutor
import com.jervis.service.agent.finalizer.TaskResolutionChecker
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Owns the planning loop: creates or loads a plan and executes steps one by one using PlanExecutor.
 */
@Service
class PlanningRunner(
    private val executor: PlanExecutor,
    private val taskResolutionChecker: TaskResolutionChecker,
    private val planner: Planner,
    private val taskContextService: TaskContextService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun run(taskContext: TaskContext): Boolean {
        logger.info { "AGENT_LOOP_START: Planning loop for context: ${taskContext.id}" }
        logger.debug {
            "PLANNING_RUNNER_INITIAL_PLANS: ${taskContext.plans.map { "planId=${it.id}, status=${it.status}, steps=${it.steps.size}" }}"
        }

        // Main execution loop - continue until all plans are completed or failed
        while (taskContext.plans
                .count { it.status != PlanStatus.FAILED && it.status != PlanStatus.COMPLETED } > 0
        ) {
            val activePlansCount =
                taskContext.plans.count { it.status != PlanStatus.FAILED && it.status != PlanStatus.COMPLETED }
            logger.debug {
                "PLANNING_RUNNER_LOOP_ITERATION: activePlansCount=$activePlansCount, planStatuses=${
                    taskContext.plans.map {
                        "${it.id}:${it.status}"
                    }
                }"
            }
            executor.execute(taskContext)
        }

        // Enhanced resolution checking with re-planning capability
        val resolutionResult = taskResolutionChecker.performLlmAnalysis(taskContext)
        logger.info {
            "PLANNING_RUNNER_RESOLUTION_CHECK: complete=${resolutionResult.complete}, "
        }

        if (!resolutionResult.complete && resolutionResult.missingRequirements.isNotEmpty()) {
            logger.info { "PLANNING_RUNNER_INCOMPLETE_TASK: Creating additional plan to address missing requirements" }

            try {
                // Create a comprehensive description of what's missing for re-planning
                val missingRequirementsDescription = buildMissingRequirementsPrompt(resolutionResult)

                // Create a new plan to address missing requirements
                val additionalPlan = createAdditionalPlan(taskContext, missingRequirementsDescription)

                // Add the new plan to the context
                taskContext.plans += additionalPlan
                taskContextService.save(taskContext)

                logger.info {
                    "PLANNING_RUNNER_ADDITIONAL_PLAN_CREATED: planId=${additionalPlan.id}, " +
                        "steps=${additionalPlan.steps.size}, addressing ${resolutionResult.missingRequirements.size} missing items"
                }

                // Execute the additional plan
                return run(taskContext)
            } catch (e: Exception) {
                logger.error(e) { "PLANNING_RUNNER_REPLAN_FAILED: Unable to create additional plan for missing requirements" }
                // Continue with an original result even if re-planning fails
            }
        }

        logger.info { "AGENT_LOOP_END: Final resolution status = ${resolutionResult.complete}" }
        logger.debug {
            "PLANNING_RUNNER_FINAL_PLANS: ${taskContext.plans.map { "planId=${it.id}, status=${it.status}, steps=${it.steps.size}" }}"
        }

        return resolutionResult.complete
    }

    /**
     * Creates a comprehensive prompt describing missing requirements for re-planning
     */
    private fun buildMissingRequirementsPrompt(resolutionResult: TaskResolutionChecker.LlmAnalysisResult): String =
        buildString {
            appendLine("The following requirements are missing or incomplete and need to be addressed:")
            resolutionResult.missingRequirements.forEachIndexed { index, item ->
                appendLine("${index + 1}. $item")
            }
            appendLine()
            appendLine("Please create a plan to complete these missing requirements.")
        }

    /**
     * Creates an additional plan to address missing requirements
     */
    private suspend fun createAdditionalPlan(
        taskContext: TaskContext,
        missingRequirementsDescription: String,
    ): Plan {
        // Create a mock original plan context for the planner
        val mockOriginalPlan =
            Plan(
                id = ObjectId(),
                contextId = taskContext.id,
                originalQuestion = missingRequirementsDescription,
                originalLanguage = "en",
                englishQuestion = missingRequirementsDescription,
                status = PlanStatus.CREATED,
                steps = emptyList(),
                contextSummary = "Additional plan to address incomplete requirements",
                finalAnswer = null,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )

        // Use the existing planner to create steps for missing requirements
        return planner.createPlan(taskContext, mockOriginalPlan)
    }
}
