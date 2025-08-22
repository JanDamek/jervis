package com.jervis.service.agent.coordinator

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.entity.mongo.AuditType
import com.jervis.service.agent.AgentConstants
import com.jervis.service.agent.ScopeResolutionService
import com.jervis.service.agent.context.ContextService
import com.jervis.service.agent.context.ContextTool
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.finalizer.Finalizer
import com.jervis.service.agent.planner.PlanningRunner
import com.jervis.service.audit.AuditLogService
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * ChatCoordinatorService is now the main orchestrator for incoming chat requests.
 * It encapsulates the orchestration flow previously implemented in ChatCoordinator.
 */
@Service
class AgentOrchestratorService(
    private val contextService: ContextService,
    private val taskContextService: TaskContextService,
    private val language: LanguageOrchestrator,
    private val planningRunner: PlanningRunner,
    private val finalizer: Finalizer,
    private val auditLog: AuditLogService,
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val contextTool: ContextTool,
    private val scopeResolver: ScopeResolutionService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun handle(
        text: String,
        ctx: ChatRequestContext,
    ): ChatResponse {
        // 1) Detect language and create initial context snapshot
        val requestLang = language.detectLanguage(text)
        val initialCtx =
            contextService.persistContext(
                clientName = ctx.clientName,
                projectName = ctx.projectName,
                autoScope = ctx.autoScope,
                englishText = null,
                contextId = null,
                sourceLanguage = requestLang,
            )

        // 2) Detect scope and translate to English (with audit)
        val clients = clientService.list()
        val projects = projectService.getAllProjects()
        val auditId =
            auditLog.start(
                type = AuditType.TRANSLATION_DETECTION,
                inputText = text,
                userPrompt = AgentConstants.AuditPrompts.SCOPE_DETECTION, // overwritten by orchestrator prompt internally
                clientHint = ctx.clientName,
                projectHint = ctx.projectName,
                contextId = initialCtx.id,
            )
        val detection = try {
            val det = language.detectScopeAndTranslate(
                text = text,
                clientHint = ctx.clientName,
                projectHint = ctx.projectName,
                clients = clients,
                projects = projects,
            )
            auditLog.complete(auditId, responseText = "client=${det.client}; project=${det.project}")
            det
        } catch (e: Exception) {
            auditLog.complete(auditId, errorText = e.message ?: e.toString())
            logger.warn(e) { "Scope detection failed, will fallback to defaults" }
            LanguageOrchestrator.ScopeDetectionResult(client = ctx.clientName, project = ctx.projectName, englishText = null)
        }

        val resolved = scopeResolver.resolve(ctx.clientName, ctx.projectName)
        val finalClient = resolved.clientName
        val finalProject = resolved.projectName

        // 3) Persist detected scope and translated text
        contextService.persistContext(
            clientName = finalClient,
            projectName = finalProject,
            autoScope = ctx.autoScope,
            englishText = detection.englishText,
            contextId = initialCtx.id,
            sourceLanguage = requestLang,
        )
        // Apply resolved context globally (UI hooks etc.)
        contextTool.applyContext(finalClient, finalProject)

        // 3b) Create task context linked to ContextDocument for long-running planner/executor
        taskContextService.create(
            contextId = initialCtx.id,
            clientName = finalClient,
            projectName = finalProject,
            initialQuery = detection.englishText ?: text,
        )

        // Build validation/warning message based on original hints
        val chosen = finalProject?.trim()
        val baseMsg = chosen?.let { "OK, working in project $it" }
            ?: "OK, context applied for client ${finalClient ?: "unknown"}"
        val msg = if (resolved.warnings.isEmpty()) baseMsg else "$baseMsg. ${resolved.warnings.joinToString("; ")}" 

        // 4) Plan using resolved context only (Planner relies solely on context)
        val planResult = planningRunner.run(contextId = initialCtx.id)

        val finalPlanResult = planResult.copy(
            message = msg,
            chosenProject = finalProject ?: "",
            detectedClient = finalClient,
            detectedProject = finalProject,
        )

        // 5) Finalize response for the user
        return finalizer.finalize(
            plannerResult = finalPlanResult,
            requestLanguage = requestLang,
            englishText = planResult.englishText ?: detection.englishText,
        )
    }
}
