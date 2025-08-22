package com.jervis.service.agent.execution

import com.jervis.entity.mongo.PlanDocument
import com.jervis.entity.mongo.PlanStatus
import com.jervis.entity.mongo.StepStatus
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.mcp.McpToolRegistry
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Executes the first pending plan step using registered MCP tools.
 */
@Service
class PlanExecutorImpl(
    private val mcpToolRegistry: McpToolRegistry,
    private val taskContextRepo: TaskContextMongoRepository,
) : PlanExecutor {
    private val logger = KotlinLogging.logger {}

    override suspend fun execute(plan: PlanDocument): PlanDocument {
        val now = Instant.now()
        if (plan.steps.isEmpty()) {
            logger.info { "EXECUTOR_NO_STEPS: plan=${plan.id} -> COMPLETED" }
            return plan.copy(status = PlanStatus.COMPLETED, updatedAt = now)
        }

        val idx = plan.steps.indexOfFirst { it.status == StepStatus.PENDING }
        if (idx < 0) {
            logger.info { "EXECUTOR_NO_PENDING: plan=${plan.id} -> COMPLETED" }
            return plan.copy(status = PlanStatus.COMPLETED, updatedAt = now)
        }

        val step = plan.steps[idx]
        val tool = mcpToolRegistry.byName(step.name)
            ?: run {
                logger.error { "EXECUTOR_TOOL_MISSING: step='${step.name}' plan=${plan.id} -> FAILED" }
                return plan.copy(status = PlanStatus.FAILED, updatedAt = now)
            }

        val context = taskContextRepo.findByContextId(plan.contextId)
            ?: run {
                logger.error { "EXECUTOR_CONTEXT_MISSING: contextId=${plan.contextId} plan=${plan.id} -> FAILED" }
                return plan.copy(status = PlanStatus.FAILED, updatedAt = now)
            }

        logger.info { "EXECUTOR_STEP_START: index=${idx} step='${step.name}' plan=${plan.id}" }
        logger.debug { "EXECUTOR_STEP_TOOL: tool='${tool.name}', parameters={}" }

        val result = try {
            tool.execute(context = context, parameters = emptyMap())
        } catch (e: Exception) {
            logger.error(e) { "EXECUTOR_STEP_EXCEPTION: index=${idx} step='${step.name}'" }
            return plan.copy(status = PlanStatus.FAILED, updatedAt = now)
        }

        logger.info { "EXECUTOR: Tool '${tool.name}' finished with success=${result.success}" }
        logger.debug { "EXECUTOR_OUTPUT: ${result.output}" }

        val newStatusForStep = if (result.success) StepStatus.DONE else StepStatus.ERROR
        val outputText = result.output.toString()
        val newStep = step.copy(status = newStatusForStep, output = outputText)
        val newSteps = plan.steps.toMutableList().apply { this[idx] = newStep }.toList()

        val newPlanStatus = when {
            newStatusForStep == StepStatus.ERROR -> PlanStatus.FAILED
            newSteps.any { it.status == StepStatus.PENDING } -> PlanStatus.RUNNING
            else -> PlanStatus.COMPLETED
        }
        return plan.copy(status = newPlanStatus, steps = newSteps, updatedAt = now)
    }
}
