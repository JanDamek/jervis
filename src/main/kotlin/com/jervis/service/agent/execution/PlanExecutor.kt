package com.jervis.service.agent.execution

import com.jervis.entity.mongo.PlanDocument

/**
 * Executes a plan step-by-step. For now, this is only a stub that marks the plan as completed
 * without performing any real tool action. Real implementation will:
 *  - use ToolRegistry to find applicable tools (MCP) for steps
 *  - execute the first non-executed step
 *  - persist outputs back to Context and Plan
 *  - re-validate/update plan after each step
 */
fun interface PlanExecutor {
    suspend fun execute(plan: PlanDocument): PlanDocument
}
