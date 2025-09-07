package com.jervis.domain.plan

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Extension methods for Plan class to support dynamic step management
 * as required for autonomous MCP tools.
 */

/**
 * Prepends new steps to the beginning of the plan.
 * This allows tools to add prerequisite steps (e.g., RAG queries, user clarifications)
 * before their own execution.
 */
fun Plan.prependSteps(vararg newSteps: PlanStep): Plan = prependSteps(newSteps.toList())

/**
 * Prepends new steps to the beginning of the plan.
 * This allows tools to add prerequisite steps (e.g., RAG queries, user clarifications)
 * before their own execution.
 */
fun Plan.prependSteps(newSteps: List<PlanStep>): Plan {
    if (newSteps.isEmpty()) return this

    // Adjust order numbers for existing steps
    val adjustedExistingSteps =
        steps.map { step ->
            step.copy(order = step.order + newSteps.size)
        }

    // Set proper order numbers for new steps
    val orderedNewSteps =
        newSteps.mapIndexed { index, step ->
            step.copy(order = index + 1)
        }

    return copy(
        steps = orderedNewSteps + adjustedExistingSteps,
        updatedAt = Instant.now(),
    )
}

/**
 * Appends new steps to the end of the plan.
 * This allows tools to add follow-up steps after their own execution.
 */
fun Plan.appendSteps(vararg newSteps: PlanStep): Plan = appendSteps(newSteps.toList())

/**
 * Appends new steps to the end of the plan.
 * This allows tools to add follow-up steps after their own execution.
 */
fun Plan.appendSteps(newSteps: List<PlanStep>): Plan {
    if (newSteps.isEmpty()) return this

    val maxOrder = steps.maxOfOrNull { it.order } ?: 0

    // Set proper order numbers for new steps
    val orderedNewSteps =
        newSteps.mapIndexed { index, step ->
            step.copy(order = maxOrder + index + 1)
        }

    return copy(
        steps = steps + orderedNewSteps,
        updatedAt = Instant.now(),
    )
}

/**
 * Appends a single new step to the end of the plan.
 * Convenience method for TwoPhasePlanner compatibility.
 */
fun Plan.appendNewStep(
    name: String,
    taskDescription: String,
): Plan {
    val newStep =
        createPlanStep(
            planId = id,
            contextId = contextId,
            name = name,
            taskDescription = taskDescription,
        )
    return appendSteps(newStep)
}

/**
 * Prepends a single new step to the beginning of the plan.
 * Convenience method for TwoPhasePlanner compatibility.
 */
fun Plan.prependNewStep(
    name: String,
    taskDescription: String,
): Plan {
    val newStep =
        createPlanStep(
            planId = id,
            contextId = contextId,
            name = name,
            taskDescription = taskDescription,
        )
    return prependSteps(newStep)
}

/**
 * Creates a new PlanStep with the given parameters.
 * Helper function to make it easier for tools to create steps.
 */
fun createPlanStep(
    planId: ObjectId,
    contextId: ObjectId,
    name: String,
    taskDescription: String,
    order: Int = 1,
): PlanStep =
    PlanStep(
        id = ObjectId(),
        order = order,
        planId = planId,
        contextId = contextId,
        name = name,
        taskDescription = taskDescription,
        status = StepStatus.PENDING,
        output = null,
    )
