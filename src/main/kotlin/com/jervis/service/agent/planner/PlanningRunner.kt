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

        while (taskContext.plans
                .count { it.status != PlanStatus.FAILED && it.status != PlanStatus.COMPLETED } > 0
        ) {
            executor.execute(taskContext)
        }

        logger.info { "AGENT_LOOP_END" }
    }
}
