package com.jervis.service.agent.coordinator

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.entity.mongo.AuditType
import com.jervis.service.agent.context.ContextService
import com.jervis.service.agent.context.ContextTool
import com.jervis.service.agent.context.TaskContextService
import com.jervis.service.agent.finalizer.Finalizer
import com.jervis.service.agent.planner.Planner
import com.jervis.service.audit.AuditLogService
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * ChatCoordinator is the main orchestrator for incoming chat requests.
 *
 * Flow:
 * 1. Detect request language and create initial context.
 * 2. Detect client/project and translate to English using LanguageOrchestrator, with auditing.
 * 3. Persist detected scope and english text to context.
 * 4. Delegate planning to Planner with autoScope=false (context already resolved).
 * 5. Finalize the response in the original request language.
 */
@Service
class ChatCoordinator(
    private val contextService: ContextService,
    private val taskContextService: TaskContextService,
    private val language: LanguageOrchestrator,
    private val planner: Planner,
    private val finalizer: Finalizer,
    private val auditLog: AuditLogService,
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val contextTool: ContextTool,
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
                userPrompt = "scope-detection", // overwritten by orchestrator prompt internally
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

        val finalClient = ctx.clientName
        val finalProject = ctx.projectName

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
        val chosen = ctx.projectName?.trim()
        val warnings = mutableListOf<String>()
        if (chosen != null) {
            val project = projects.firstOrNull { it.name.equals(chosen, ignoreCase = true) }
            if (project == null) {
                warnings += "Warning: project '$chosen' not found"
            } else if (ctx.clientName != null) {
                val client = clients.firstOrNull { it.name.equals(ctx.clientName, ignoreCase = true) }
                if (client != null && project.clientId != client.id) {
                    warnings += "Warning: project '$chosen' does not belong to client '${ctx.clientName}'"
                }
            }
        }
        val baseMsg = chosen?.let { "OK, working in project $it" }
            ?: "OK, context applied for client ${ctx.clientName ?: "unknown"}"
        val msg = if (warnings.isEmpty()) baseMsg else "$baseMsg. ${warnings.joinToString("; ")}" 

        // 4) Plan using resolved context only (Planner relies solely on context)
        var planResult = planner.execute(contextId = initialCtx.id)

        var iterations = 0
        while (planResult.shouldContinue && iterations < 10) {
            planResult = planner.execute(contextId = initialCtx.id)
            iterations++
        }

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
