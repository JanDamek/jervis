package com.jervis.service.agent.planner

import com.jervis.domain.plan.PlanStatus
import com.jervis.domain.plan.StepStatus
import com.jervis.entity.mongo.PlanDocument
import com.jervis.repository.mongo.PlanMongoRepository
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.agent.execution.PlanExecutor
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Owns the planning loop: creates or loads a plan and executes steps one by one using PlanExecutor.
 */
@Service
class PlanningRunner(
    private val planRepo: PlanMongoRepository,
    private val taskContextRepo: TaskContextMongoRepository,
    private val planner: Planner,
    private val executor: PlanExecutor,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun run(contextId: ObjectId) {
        logger.info { "AGENT_LOOP_START: Planning loop for context: $contextId" }
        val taskContext =
            taskContextRepo.findByContextId(contextId)
                ?: run {
                    logger.error { "RUNNER_CONTEXT_MISSING: contextId=$contextId" }
                    return
                }

        // Load or create a plan for this context
        val existing: PlanDocument? = planRepo.findByContextId(contextId)
        var plan: PlanDocument =
            if (existing == null || existing.steps.isEmpty()) {
                val created = planner.createPlan(taskContext)
                planRepo.save(created)
            } else {
                existing
            }

        while (true) {
            val pendingIndex = plan.steps.indexOfFirst { it.status == StepStatus.PENDING }
            if (pendingIndex < 0) break
            val step = plan.steps[pendingIndex]
            logger.info { "RUNNER: Executing step $pendingIndex ('${step.name}') using tool '${step.name}'" }
            logger.debug { "RUNNER_PARAMS: ${step.parameters}" }

            executor.execute(plan)
            plan = planRepo.save(plan)

            if (plan.status == PlanStatus.FAILED || plan.status == PlanStatus.COMPLETED) break
        }

        val shouldContinue = plan.status == PlanStatus.RUNNING || plan.steps.any { it.status == StepStatus.PENDING }

        logger.info { "AGENT_LOOP_END: shouldContinue=$shouldContinue" }
    }
}
