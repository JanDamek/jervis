package com.jervis.service.agent.planner

import com.jervis.domain.plan.GoalDto
import com.jervis.domain.plan.PlanStep
import com.jervis.service.agent.planner.impl.DefaultTopologicalSorter
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Sequences planning steps from multiple goals, resolving dependencies.
 *
 * This service uses a topological sort to order goals and then flattens their steps into a single,
 * ordered list. It recalculates local step dependencies into absolute indices,
 * ensuring the final plan is executable. The implementation is purely functional and
 * avoids the mutable state.
 *
 * @property topologicalSorter The service responsible for topologically sorting goals.
 */
@Service
class PlanStepSequencer(
    private val topologicalSorter: DefaultTopologicalSorter,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Sequences steps from a list of goals, resolving their dependencies.
     *
     * @param goalStepPairs A list of pairs, where each pair contains a GoalDto and its associated PlanSteps.
     * @return A flattened list of all PlanSteps in the correct execution order.
     */
    fun sequenceSteps(goalStepPairs: List<Pair<GoalDto, List<PlanStep>>>): List<PlanStep> {
        if (goalStepPairs.isEmpty()) {
            return emptyList()
        }

        logger.debug { "Sequencing steps from ${goalStepPairs.size} goals" }

        val goalToStepsMap = goalStepPairs.toMap()
        val sortedGoals = topologicalSorter.sortGoalsByDependencies(goalStepPairs.map { it.first })

        return createFinalSteps(sortedGoals, goalToStepsMap)
    }

    /**
     * Creates the final list of steps by flattening goals and recalculating dependencies.
     *
     * This method uses a purely functional approach with `scan` and `flatMap` to build
     * the list of steps and their absolute dependencies without using mutable lists.
     *
     * @param sortedGoals A list of goals already sorted by their dependencies.
     * @param goalToStepsMap A map from goals to their respective steps.
     * @return The final list of steps with correctly recalculated dependencies.
     */
    private fun createFinalSteps(
        sortedGoals: List<GoalDto>,
        goalToStepsMap: Map<GoalDto, List<PlanStep>>,
    ): List<PlanStep> {
        val allSteps =
            sortedGoals.flatMap { goal ->
                goalToStepsMap[goal].orEmpty().map { step ->
                    step.copy(stepGroup = "goal-${goal.goalId}")
                }
            }

        val goalStartIndices =
            sortedGoals.associateWith { goal -> allSteps.indexOfFirst { it.stepGroup == "goal-${goal.goalId}" } }

        return allSteps.mapIndexed { absoluteIndex, step ->
            val goalId = extractGoalIdFromGroup(step.stepGroup)
            val goalStartIndex = goalStartIndices[sortedGoals.first { it.goalId == goalId }] ?: 0

            val absoluteDependencies =
                step.stepDependsOn
                    .mapNotNull { localIndex ->
                        val absoluteDep = goalStartIndex + localIndex
                        if (absoluteDep in 0..<absoluteIndex) absoluteDep else null
                    }

            step.copy(order = absoluteIndex, stepDependsOn = absoluteDependencies)
        }
    }

    /**
     * Extracts the goal ID from a step's group string.
     *
     * @param stepGroup The string identifier for the step's group.
     * @return The extracted integer ID or 0 if parsing fails.
     */
    private fun extractGoalIdFromGroup(stepGroup: String?): Int = stepGroup?.removePrefix("goal-")?.toIntOrNull() ?: 0
}
