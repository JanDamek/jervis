package com.jervis.service.agent.planner.impl

import com.jervis.domain.plan.GoalDto
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Topological sorter for goal dependency resolution.
 * Handles goal dependency resolution with proper cycle detection and error handling.
 */
@Service
class DefaultTopologicalSorter {
    private val logger = KotlinLogging.logger {}

    /**
     * Sorts goals by their dependencies using topological sort algorithm.
     * Uses functional approach with immutable collections and proper error handling.
     */
    fun sortGoalsByDependencies(goals: List<GoalDto>): List<GoalDto> {
        if (goals.isEmpty()) {
            return emptyList()
        }

        logger.debug { "Sorting ${goals.size} goals by dependencies" }

        val result = performTopologicalSort(goals)
        logger.debug { "Topological sort successful: ${result.map { "${it.goalId}(deps:${it.dependsOn})" }}" }
        return result
    }

    /**
     * Performs the actual topological sort using depth-first search.
     * Throws specific exceptions for different error cases.
     */
    private fun performTopologicalSort(goals: List<GoalDto>): List<GoalDto> {
        val goalMap = goals.associateBy { it.goalId }
        val visited = mutableSetOf<Int>()
        val visiting = mutableSetOf<Int>()
        val result = mutableListOf<GoalDto>()

        fun visit(goalId: Int) {
            if (goalId in visiting) {
                throw CyclicDependencyException("Cycle detected involving goal $goalId", visiting.toList())
            }

            if (goalId in visited) return

            val goal =
                goalMap[goalId]
                    ?: throw MissingDependencyException("Goal $goalId not found", goalId, goalId)

            visiting.add(goalId)

            // Process all dependencies first
            for (depId in goal.dependsOn) {
                if (depId !in goalMap) {
                    throw MissingDependencyException("Goal $goalId depends on non-existent goal $depId", goalId, depId)
                }
                visit(depId)
            }

            visiting.remove(goalId)
            visited.add(goalId)
            result.add(goal)
        }

        // Visit all goals to ensure complete ordering
        goals.forEach { goal ->
            if (goal.goalId !in visited) {
                visit(goal.goalId)
            }
        }

        return result
    }

    /**
     * Custom exception for cyclic dependency detection.
     */
    private class CyclicDependencyException(
        message: String,
        val goalIds: List<Int>,
    ) : Exception(message)

    /**
     * Custom exception for missing dependency detection.
     */
    private class MissingDependencyException(
        message: String,
        val goalId: Int,
        val missingDepId: Int,
    ) : Exception(message)
}
