package com.jervis.service.mcp

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository

/**
 * Base interface for MCP Tools executed by the planner/executor.
 * Tools should be side-effect free unless explicitly designed to persist changes.
 * All context information is available in the Plan object.
 */
interface McpTool {
    val promptRepository: PromptRepository
    val name: PromptTypeEnum
    val description: String
        get() = promptRepository.getMcpToolDescription(name)

    /**
     * Execute the tool using the plan and task description from planner.
     * Plan contains all context: clientDocument, projectDocument, quick, backgroundMode, etc.
     * Return ToolResult representing either success (ToolOkResult) or error (ToolErrorResult).
     * The output is always a String.
     */
    suspend fun execute(
        plan: Plan,
        taskDescription: String,
        stepContext: String = "",
    ): ToolResult
}
