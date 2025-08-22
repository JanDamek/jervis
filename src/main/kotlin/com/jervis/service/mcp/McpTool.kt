package com.jervis.service.mcp

import com.jervis.entity.mongo.TaskContextDocument

/**
 * Base interface for MCP Tools executed by the planner/executor.
 * Tools should be side-effect free unless explicitly designed to persist changes.
 */
interface McpTool {
    val name: String
    val description: String

    /**
     * Execute the tool using the current task context and parameters.
     * Return ToolResult with success flag and output payload.
     */
    suspend fun execute(
        context: TaskContextDocument,
        parameters: Map<String, Any>
    ): ToolResult
}

/**
 * Standardized result returned by MCP tools.
 */
data class ToolResult(
    val success: Boolean,
    val output: Any,
    val errorMessage: String? = null,
)
