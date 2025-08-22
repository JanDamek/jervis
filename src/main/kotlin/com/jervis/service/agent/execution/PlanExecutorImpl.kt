package com.jervis.service.agent.execution

import com.jervis.entity.mongo.PlanDocument
import com.jervis.entity.mongo.PlanStatus
import com.jervis.entity.mongo.StepStatus
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
) : PlanExecutor {
    private val logger = KotlinLogging.logger {}

    override suspend fun execute(plan: PlanDocument): PlanDocument {
        val now = Instant.now()
        if (plan.steps.isEmpty()) {
            logger.info { "PlanExecutor: no steps for plan ${plan.id}, marking COMPLETED" }
            return plan.copy(status = PlanStatus.COMPLETED, updatedAt = now)
        }

        val idx = plan.steps.indexOfFirst { it.status == StepStatus.PENDING }
        if (idx < 0) {
            logger.info { "PlanExecutor: no pending steps for plan ${plan.id}, marking COMPLETED" }
            return plan.copy(status = PlanStatus.COMPLETED, updatedAt = now)
        }

        val step = plan.steps[idx]
        val tool = mcpToolRegistry.byName(step.name)
            ?: return plan.copy(status = PlanStatus.FAILED, updatedAt = now)

        val output = try {
            tool.execute(action = step.name, contextId = plan.contextId)
        } catch (e: Exception) {
            logger.error(e) { "PlanExecutor: step '${step.name}' failed in plan ${plan.id}" }
            return plan.copy(
                status = PlanStatus.FAILED,
                updatedAt = now,
            )
        }

        val newStep = step.copy(status = StepStatus.DONE, output = output)
        val newSteps = plan.steps.toMutableList().apply { this[idx] = newStep }.toList()
        val hasPending = newSteps.any { it.status == StepStatus.PENDING }
        val newStatus = if (hasPending) PlanStatus.RUNNING else PlanStatus.COMPLETED
        return plan.copy(status = newStatus, steps = newSteps, updatedAt = now)
    }
}
