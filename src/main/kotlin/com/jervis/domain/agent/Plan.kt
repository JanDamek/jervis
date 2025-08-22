package com.jervis.domain.agent

/**
 * Core agent planning domain types.
 */

enum class TaskStatus { PLANNING, EXECUTING, AWAITING_USER, FINALIZING, COMPLETED, FAILED }

enum class StepStatus { PENDING, RUNNING, COMPLETED, FAILED }

/**
 * A single executable step in the plan.
 */
data class PlanStep(
    val id: Int,
    val description: String,
    val toolName: String,
    val parameters: Map<String, Any?>,
    var status: StepStatus = StepStatus.PENDING,
    var resultKey: String? = null,
)

/**
 * The complete plan for achieving a goal.
 */
data class Plan(
    val goal: String,
    val steps: List<PlanStep>,
    var currentStepIndex: Int = 0,
)