package com.jervis.service.agent.planner

import com.jervis.entity.mongo.PlanDocument
import com.jervis.entity.mongo.PlanStatus
import com.jervis.entity.mongo.PlanStep
import com.jervis.entity.mongo.StepStatus
import com.jervis.repository.mongo.PlanMongoRepository
import com.jervis.service.agent.AgentConstants
import com.jervis.service.agent.execution.PlanExecutor
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class Planner(
    private val planRepo: PlanMongoRepository,
    private val executor: PlanExecutor,
) {
    suspend fun execute(contextId: ObjectId): PlannerResult {
        // Load or create plan for this context
        val existing = planRepo.findByContextId(contextId)
        val planToRun = when {
            existing == null -> {
                val initial = PlanDocument(
                    contextId = contextId,
                    status = PlanStatus.CREATED,
                    steps = listOf(
                        PlanStep(
                            name = AgentConstants.DefaultSteps.SCOPE_RESOLVE,
                            status = StepStatus.PENDING,
                            output = null,
                        ),
                        PlanStep(
                            name = AgentConstants.DefaultSteps.CONTEXT_ECHO,
                            status = StepStatus.PENDING,
                            output = null,
                        ),
                        PlanStep(
                            name = AgentConstants.DefaultSteps.LANGUAGE_NORMALIZE,
                            status = StepStatus.PENDING,
                            output = null,
                        ),
                        PlanStep(
                            name = AgentConstants.DefaultSteps.RAG_QUERY,
                            status = StepStatus.PENDING,
                            output = null,
                        ),
                    ),
                )
                planRepo.save(initial)
            }
            existing.steps.isEmpty() -> {
                val enriched = existing.copy(
                    status = PlanStatus.CREATED,
                    steps = listOf(
                        PlanStep(
                            name = AgentConstants.DefaultSteps.SCOPE_RESOLVE,
                            status = StepStatus.PENDING,
                            output = null,
                        ),
                        PlanStep(
                            name = AgentConstants.DefaultSteps.CONTEXT_ECHO,
                            status = StepStatus.PENDING,
                            output = null,
                        ),
                        PlanStep(
                            name = AgentConstants.DefaultSteps.LANGUAGE_NORMALIZE,
                            status = StepStatus.PENDING,
                            output = null,
                        ),
                        PlanStep(
                            name = AgentConstants.DefaultSteps.RAG_QUERY,
                            status = StepStatus.PENDING,
                            output = null,
                        ),
                    ),
                )
                planRepo.save(enriched)
            }
            else -> existing
        }

        val updated = executor.execute(planToRun)
        val persisted = planRepo.save(updated)
        val shouldContinue = persisted.status == PlanStatus.RUNNING || persisted.steps.any { it.status == StepStatus.PENDING }
        val normalizedOutput =
            persisted.steps.lastOrNull { it.name == AgentConstants.DefaultSteps.LANGUAGE_NORMALIZE && it.status == StepStatus.DONE }
                ?.output
                ?.takeIf { !it.isNullOrBlank() }
                ?: persisted.steps.lastOrNull { it.status == StepStatus.DONE }
                    ?.output
                    ?.takeIf { !it.isNullOrBlank() }

        return PlannerResult(
            message = "",
            chosenProject = "",
            englishText = normalizedOutput,
            shouldContinue = shouldContinue,
        )
    }
}
