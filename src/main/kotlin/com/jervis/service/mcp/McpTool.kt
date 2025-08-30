package com.jervis.service.mcp

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.service.mcp.domain.ToolResult

/**
 * Base interface for MCP Tools executed by the planner/executor.
 * Tools should be side-effect free unless explicitly designed to persist changes.
 */
interface McpTool {
    val name: String
    val description: String

    /**
     * Execute the tool using the current task context and parameters.
     * Return ToolResult representing either success (ToolOkResult) or error (ToolErrorResult).
     * The output is always a String.
     */
    suspend fun execute(
        context: TaskContextDocument,
        parameters: String,
    ): ToolResult
}
