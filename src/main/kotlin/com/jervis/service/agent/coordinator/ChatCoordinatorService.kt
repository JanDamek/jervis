package com.jervis.service.agent.coordinator

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.service.agent.finalizer.Finalizer
import com.jervis.service.agent.planner.PlanningRunner
import com.jervis.service.agent.context.TaskContextService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * ChatCoordinatorService is now the main orchestrator for incoming chat requests.
 * It encapsulates the orchestration flow previously implemented in ChatCoordinator.
 */
@Service
class AgentOrchestratorService(
    private val taskContextService: TaskContextService,
    private val planningRunner: PlanningRunner,
    private val finalizer: Finalizer,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun handle(
        text: String,
        ctx: ChatRequestContext,
    ): ChatResponse {
        logger.info { "AGENT_START: Handling query for client='${ctx.clientName}', project='${ctx.projectName}'" }
        logger.debug { "AGENT_QUERY: \"${text}\"" }

        val contextId = ObjectId.get()
        taskContextService.create(
            contextId = contextId,
            clientName = ctx.clientName,
            projectName = ctx.projectName,
            initialQuery = text,
        )

        logger.info { "AGENT_LOOP_START: Planning loop for context: $contextId" }
        val planResult = planningRunner.run(contextId = contextId)
        logger.info { "AGENT_LOOP_END: shouldContinue=${planResult.shouldContinue}" }

        val response = finalizer.finalize(
            plannerResult = planResult,
            requestLanguage = "en",
            englishText = planResult.englishText ?: text,
        )
        logger.info { "AGENT_END: Responding to user with: \"${response.message}\"" }
        return response
    }
}
