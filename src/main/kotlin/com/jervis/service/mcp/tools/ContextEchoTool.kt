package com.jervis.service.mcp.tools

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.service.agent.AgentConstants
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.ToolResult
import org.springframework.stereotype.Service

/**
 * Simple tool that echoes the initialQuery stored in the provided TaskContextDocument.
 * Useful as a deterministic first step to exercise the planning/execution loop.
 */
@Service
class ContextEchoTool : McpTool {
    override val name: String = AgentConstants.DefaultSteps.CONTEXT_ECHO
    override val description: String = "Echo the current initial query from the task context."

    override suspend fun execute(context: TaskContextDocument, parameters: Map<String, Any>): ToolResult {
        val text = context.initialQuery.orEmpty()
        return ToolResult(success = true, output = text)
    }
}
