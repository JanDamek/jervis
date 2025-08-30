package com.jervis.service.agent.planner

import com.jervis.domain.plan.PlanStatus
import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.service.agent.execution.PlanExecutor
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.filter
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

    suspend fun run(taskContext: TaskContextDocument) {
        logger.info { "AGENT_LOOP_START: Planning loop for context: ${taskContext.id}" }

        while (taskContext.plans
                .filter { it.status != PlanStatus.FAILED && it.status != PlanStatus.COMPLETED }
                .count() > 0
        ) {
            executor.execute(taskContext)
        }

        logger.info { "AGENT_LOOP_END" }
    }
}
