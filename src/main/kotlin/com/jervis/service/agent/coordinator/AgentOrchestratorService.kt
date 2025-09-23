package com.jervis.service.agent.coordinator

import com.jervis.domain.plan.Plan
import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.entity.mongo.PlanDocument
import com.jervis.repository.mongo.PlanMongoRepository
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.finalizer.Finalizer
import com.jervis.service.agent.planner.Planner
import com.jervis.service.agent.planner.PlanningRunner
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Main orchestrator for incoming chat requests.
 * Encapsulates the flow previously implemented in ChatCoordinator.
 */
@Service
class AgentOrchestratorService(
    private val taskContextService: TaskContextService,
    private val planningRunner: PlanningRunner,
    private val finalizer: Finalizer,
    private val languageOrchestrator: LanguageOrchestrator,
    private val planner: Planner,
    private val planMongoRepository: PlanMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun handle(
        text: String,
        ctx: ChatRequestContext,
    ): ChatResponse {
        logger.info { "AGENT_START: Handling query for client='${ctx.clientId}', project='${ctx.projectId}'" }

        val detectionResult =
            languageOrchestrator.translate(
                text = text,
                quick = ctx.quick,
            )

        val context =
            if (ctx.existingContextId != null) {
                taskContextService.findById(ctx.existingContextId)?.apply {
                    if (this.name == "New Context") {
                        this.name = detectionResult.contextName
                    }
                }
                    ?: throw IllegalArgumentException("Context with ID ${ctx.existingContextId} not found")
            } else {
                taskContextService.create(
                    clientId = ctx.clientId,
                    projectId = ctx.projectId,
                    quick = ctx.quick,
                    contextName = detectionResult.contextName,
                )
            }

        val plan =
            Plan(
                id = ObjectId.get(),
                contextId = context.id,
                originalQuestion = text,
                originalLanguage = detectionResult.originalLanguage,
                englishQuestion = detectionResult.englishText,
                questionChecklist = detectionResult.questionChecklist,
            )
        planMongoRepository.save(PlanDocument.fromDomain(plan))
        context.plans += plan

        logger.info { "AGENT_LOOP_START: Planning loop for context: ${context.id}" }
        do {
            val updatedPlan = planner.createPlan(context, plan)

            // Update the plan in context with the steps created by TwoPhasePlanner
            val planIndex = context.plans.indexOfFirst { it.id == plan.id }
            if (planIndex >= 0) {
                context.plans = context.plans.toMutableList().apply { set(planIndex, updatedPlan) }
            }

            // Save the updated plan to a repository
            planMongoRepository.save(PlanDocument.fromDomain(updatedPlan))
            taskContextService.save(context)
        } while (planningRunner.run(context).not())
        val response = finalizer.finalize(context)
        taskContextService.save(context)
        logger.info { "AGENT_END: Final response generated." }
        logger.debug { "AGENT_FINAL_RESPONSE: \"${response.message}\"" }
        return response
    }
}
