package com.jervis.service.agent.planner

import com.jervis.service.agent.AgentConstants
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Owns the planning loop: repeatedly invokes Planner.execute until no continuation or iteration cap reached.
 */
@Service
class PlanningRunner(
    private val planner: Planner,
) {
    suspend fun run(contextId: ObjectId): PlannerResult {
        var result = planner.execute(contextId)
        var iterations = 0
        while (result.shouldContinue && iterations < AgentConstants.MAX_PLANNING_ITERATIONS) {
            result = planner.execute(contextId)
            iterations++
        }
        return result
    }
}
