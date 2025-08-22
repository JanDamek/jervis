package com.jervis.service.mcp.tools

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.service.agent.AgentConstants
import com.jervis.service.agent.ScopeResolutionService
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.ToolResult
import org.springframework.stereotype.Service

/**
 * Tool that resolves client/project scope for the current context using centralized ScopeResolutionService.
 * It reads hints directly from TaskContext and returns a compact textual summary.
 */
@Service
class ScopeResolutionTool(
    private val scopeResolver: ScopeResolutionService,
) : McpTool {
    override val name: String = AgentConstants.DefaultSteps.SCOPE_RESOLVE
    override val description: String = "Resolve client/project scope using hints from the task context."

    override suspend fun execute(context: TaskContextDocument, parameters: Map<String, Any>): ToolResult {
        val resolved = scopeResolver.resolve(context.clientName, context.projectName)
        val client = resolved.clientName ?: "unknown"
        val project = resolved.projectName ?: "unknown"
        val warnings = if (resolved.warnings.isEmpty()) "" else "; warnings=" + resolved.warnings.joinToString(" | ")
        val summary = "client=$client; project=$project$warnings"
        return ToolResult(success = true, output = summary)
    }
}
