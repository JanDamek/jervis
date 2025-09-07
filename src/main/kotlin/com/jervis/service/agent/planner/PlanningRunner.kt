package com.jervis.service.agent.planner

import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.PlanStatus
import com.jervis.service.agent.execution.PlanExecutor
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Owns the planning loop: creates or loads a plan and executes steps one by one using PlanExecutor.
 */
@Service
class PlanningRunner(
    private val executor: PlanExecutor,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun run(taskContext: TaskContext) {
        logger.info { "AGENT_LOOP_START: Planning loop for context: ${taskContext.id}" }
        logger.debug {
            "PLANNING_RUNNER_INITIAL_PLANS: ${taskContext.plans.map { "planId=${it.id}, status=${it.status}, steps=${it.steps.size}" }}"
        }

        while (taskContext.plans
                .count { it.status != PlanStatus.FAILED && it.status != PlanStatus.COMPLETED } > 0
        ) {
            val activePlansCount =
                taskContext.plans.count { it.status != PlanStatus.FAILED && it.status != PlanStatus.COMPLETED }
            logger.debug {
                "PLANNING_RUNNER_LOOP_ITERATION: activePlansCount=$activePlansCount, planStatuses=${taskContext.plans.map {
                    "${it.id}:${it.status}" }}" }
            executor.execute(taskContext)
        }

        logger.info { "AGENT_LOOP_END" }
        logger.debug {
            "PLANNING_RUNNER_FINAL_PLANS: ${taskContext.plans.map { "planId=${it.id}, status=${it.status}, steps=${it.steps.size}" }}" }
    }
}
