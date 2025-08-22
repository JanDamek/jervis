package com.jervis.service.agent.planner

import com.jervis.entity.mongo.PlanDocument
import com.jervis.entity.mongo.PlanStatus
import com.jervis.entity.mongo.PlanStep
import com.jervis.entity.mongo.StepStatus
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class Planner(
    private val planRepo: com.jervis.repository.mongo.PlanMongoRepository,
    private val executor: com.jervis.service.agent.execution.PlanExecutor,
) {
    private suspend fun createAndExecutePlan(contextId: ObjectId): Boolean {
        var plan = PlanDocument(
            contextId = contextId,
            status = PlanStatus.CREATED,
            steps = listOf(
                PlanStep(
                    name = "context.echo",
                    status = StepStatus.PENDING,
                    output = null,
                ),
            ),
        )
        plan = planRepo.save(plan)
        plan = executor.execute(plan)
        planRepo.save(plan)
        return plan.status == PlanStatus.RUNNING
    }

    suspend fun execute(contextId: ObjectId): PlannerResult {
        val shouldContinue = createAndExecutePlan(contextId)
        return PlannerResult(
            message = "",
            chosenProject = "",
            englishText = null,
            shouldContinue = shouldContinue,
        )
    }
}
