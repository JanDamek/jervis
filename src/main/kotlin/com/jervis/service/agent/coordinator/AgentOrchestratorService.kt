package com.jervis.service.agent.coordinator

import com.jervis.domain.plan.Plan
import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.entity.mongo.PlanDocument
import com.jervis.repository.mongo.PlanMongoRepository
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.finalizer.Finalizer
import com.jervis.service.agent.planner.PlanningRunner
import com.jervis.service.agent.planner.TwoPhasePlanner
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
    private val twoPhasePlanner: TwoPhasePlanner,
    private val planMongoRepository: PlanMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun handle(
        text: String,
        ctx: ChatRequestContext,
    ): ChatResponse {
        logger.info { "AGENT_START: Handling query for client='${ctx.clientId}', project='${ctx.projectId}'" }

        val scope =
            languageOrchestrator.translate(
                text = text,
                quick = ctx.quick,
            )

        val context =
            if (ctx.existingContextId != null) {
                // Use existing context if provided
                taskContextService.findById(ctx.existingContextId)
                    ?: throw IllegalArgumentException("Context with ID ${ctx.existingContextId} not found")
            } else {
                // Create new context
                taskContextService.create(
                    clientId = ctx.clientId,
                    projectId = ctx.projectId,
                    quick = ctx.quick,
                )
            }

        val plan =
            Plan(
                id = ObjectId.get(),
                contextId = context.id,
                originalQuestion = text,
                originalLanguage = scope.originalLanguage,
                englishQuestion = scope.englishText,
            )
        planMongoRepository.save(PlanDocument.fromDomain(plan))
        context.plans += plan

        logger.info { "AGENT_LOOP_START: Planning loop for context: ${context.id}" }
        twoPhasePlanner.createPlan(context, plan)
        taskContextService.save(context)
        planningRunner.run(context)
        val response = finalizer.finalize(context)
        taskContextService.save(context)
        logger.info { "AGENT_END: Final response generated." }
        logger.debug { "AGENT_FINAL_RESPONSE: \"${response.message}\"" }
        return response
    }
}
