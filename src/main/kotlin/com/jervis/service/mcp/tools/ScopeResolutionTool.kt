package com.jervis.service.mcp.tools

import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.agent.ScopeResolutionService
import com.jervis.service.agent.AgentConstants
import com.jervis.service.mcp.McpAction
import com.jervis.service.mcp.McpTool
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Tool that resolves client/project scope for the current context using centralized ScopeResolutionService.
 * It reads hints from TaskContext and returns a compact textual summary.
 */
@Service
class ScopeResolutionTool(
    private val taskContextRepo: TaskContextMongoRepository,
    private val scopeResolver: ScopeResolutionService,
) : McpTool {
    override val name: String = AgentConstants.DefaultSteps.SCOPE_RESOLVE

    override val action: McpAction = McpAction(
        type = "scope",
        content = "resolve",
        parameters = emptyMap(),
    )

    override suspend fun execute(action: String, contextId: ObjectId): String {
        val taskCtx = taskContextRepo.findByContextId(contextId)
        val resolved = scopeResolver.resolve(taskCtx?.clientName, taskCtx?.projectName)
        val client = resolved.clientName ?: "unknown"
        val project = resolved.projectName ?: "unknown"
        val warnings = if (resolved.warnings.isEmpty()) "" else "; warnings=" + resolved.warnings.joinToString(" | ")
        return "client=$client; project=$project$warnings"
    }
}
