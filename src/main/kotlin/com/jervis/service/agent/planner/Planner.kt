package com.jervis.service.agent.planner

import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.GoalDto
import com.jervis.domain.plan.Plan
import com.jervis.domain.plan.PlanStep
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Enhanced Planner using refactored components with proper error handling.
 * Coordinates two-phase planning process with Result types and functional composition.
 */
@Service
class Planner(
    private val ragFirstOrchestrator: RagFirstOrchestrator,
    private val goalPlanner: GoalPlanner,
    private val goalExpander: GoalExpander,
    private val stepSequencer: PlanStepSequencer,
) {
    private val logger = KotlinLogging.logger {}

    @Serializable
    data class StepsDto(
        val steps: List<StepDto> = listOf(StepDto()),
    )

    @Serializable
    data class StepDto(
        val stepToolName: String = "ANALYSIS_REASONING",
        val stepInstruction: String = "Summarize detected classes and methods responsible for request authorization.",
        val stepDependsOn: Int = 3,
    )

    /**
     * Creates an execution plan using refactored components with Result type handling.
     * Uses functional composition with proper error propagation.
     */
    suspend fun createPlan(
        context: TaskContext,
        plan: Plan,
    ): Plan {
        logger.info { "Creating execution plan for ${plan.id}" }

        try {
            // 1) RAG-first discovery
            val discoveryResult = ragFirstOrchestrator.discoverContext(context, plan)

            // 2) Goals planning
            val goalsResult = goalPlanner.createGoals(context, plan, discoveryResult)

            // 3) Expand goals to steps sequentially
            val stepsByGoal = mutableListOf<Pair<GoalDto, List<PlanStep>>>()
            for (goal in goalsResult) {
                val stepsResult = goalExpander.expandGoal(context, plan, goal, discoveryResult)
                stepsByGoal.add(goal to stepsResult)
            }

            // 4) Sequence steps
            val sequencingResult = stepSequencer.sequenceSteps(stepsByGoal)
            logger.info { "Execution plan created successfully: ${sequencingResult.size} steps from ${goalsResult.size} goals" }

            plan.steps = sequencingResult
            plan.updatedAt = java.time.Instant.now()
            return plan
        } catch (e: Exception) {
            logger.error { "Plan creation failed for ${plan.id}: ${e.message}" }
            throw e
        }
    }
}
