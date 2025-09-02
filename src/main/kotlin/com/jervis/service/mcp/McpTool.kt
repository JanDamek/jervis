package com.jervis.service.mcp

import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.mcp.domain.ToolResult

/**
 * Base interface for MCP Tools executed by the planner/executor.
 * Tools should be side-effect free unless explicitly designed to persist changes.
 */
interface McpTool {
    val name: String
    val description: String

    /**
     * Execute the tool using the current task context and task description from planner.
     * Return ToolResult representing either success (ToolOkResult) or error (ToolErrorResult).
     * The output is always a String.
     */
    suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult
}
