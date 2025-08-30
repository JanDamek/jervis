package com.jervis.service.agent.coordinator

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.finalizer.Finalizer
import com.jervis.service.agent.planner.Planner
import com.jervis.service.agent.planner.PlanningRunner
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import mu.KotlinLogging
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
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val planner: Planner,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun handle(
        text: String,
        ctx: ChatRequestContext,
    ): ChatResponse {
        logger.info { "AGENT_START: Handling query for client='${ctx.clientName}', project='${ctx.projectName}'" }
        logger.debug { "AGENT_QUERY: \"${text}\"" }

        // 1) Detect scope and translate input; require confirmation if detection differs from user-provided hints (unless already confirmed).
        val clients = clientService.list()
        val projects = projectService.getAllProjects()
        val scope =
            languageOrchestrator.detectScopeAndTranslate(
                text = text,
                clientHint = ctx.clientName,
                projectHint = ctx.projectName,
                clients = clients,
                projects = projects,
            )
        val suggestedClient = scope.client
        val suggestedProject = scope.project
        val mismatch =
            !ctx.confirmedScope && (
                (suggestedClient != null && !suggestedClient.equals(ctx.clientName ?: "", ignoreCase = true)) ||
                    (
                        suggestedProject != null &&
                            !suggestedProject.equals(
                                ctx.projectName ?: "",
                                ignoreCase = true,
                            )
                    )
            )
        if (mismatch) {
            return ChatResponse(
                message = "Please confirm detected scope before proceeding.",
                detectedClient = suggestedClient,
                detectedProject = suggestedProject,
                englishText = scope.englishText,
                requiresConfirmation = true,
                scopeExplanation = scope.reason,
            )
        }

        val finalClient = ctx.clientName ?: suggestedClient
        val finalProject = ctx.projectName ?: suggestedProject

        val context =
            taskContextService.create(
                clientName = finalClient,
                projectName = finalProject,
                initialQuery = scope.englishText,
                originalLanguage = scope.originalLanguage,
                quick = ctx.quick,
            )

        logger.info { "AGENT_LOOP_START: Planning loop for context: ${context.id}" }
        planner.createPlan(context)
        taskContextService.save(context)
        planningRunner.run(context)
        val response = finalizer.finalize(context)
        taskContextService.save(context)
        logger.info { "AGENT_END: Final response generated." }
        logger.debug { "AGENT_FINAL_RESPONSE: \"${response.message}\"" }
        return response
    }
}
