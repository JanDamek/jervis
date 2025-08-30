package com.jervis.service.agent.execution

import com.jervis.entity.mongo.PlanDocument

/**
 * PlanExecutor executes a plan and returns an updated plan.
 * Implementations decide how to process steps and manage status transitions.
 */
fun interface PlanExecutor {
    suspend fun execute(plan: PlanDocument)
}
