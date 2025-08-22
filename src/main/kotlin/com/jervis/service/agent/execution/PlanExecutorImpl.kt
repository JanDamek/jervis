package com.jervis.service.agent.execution

import com.jervis.entity.mongo.PlanDocument
import com.jervis.entity.mongo.PlanStatus
import com.jervis.service.mcp.McpToolRegistry
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Temporary stub implementation that simply marks the plan as COMPLETED.
 */
@Service
class PlanExecutorImpl(
    private val mcpToolRegistry: McpToolRegistry,
) : PlanExecutor {
    private val logger = KotlinLogging.logger {}

    override suspend fun execute(plan: PlanDocument): PlanDocument {
        logger.info { "PlanExecutorStub: completing plan ${plan.id}" }
        return plan.copy(status = PlanStatus.COMPLETED, updatedAt = Instant.now())
    }
}
