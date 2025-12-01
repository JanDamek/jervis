package com.jervis.mcp

import com.jervis.configuration.prompts.ToolTypeEnum
import com.jervis.domain.plan.Plan
import com.jervis.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository

/**
 * Canonical MCP Tool contract for JERVIS.
 * Public interface lives in com.jervis.mcp; implementations remain under *_internal according to guidelines.
 */
interface McpTool<T : Any> {
    val promptRepository: PromptRepository
    val name: ToolTypeEnum
    val descriptionObject: T

    /**
     * Short description for planner - what the tool does and when to use it.
     */
    val plannerDescription: String
        get() = promptRepository.getMcpToolPlannerDescription(name)

    /**
     * Detailed description for Tool Reasoning with JSON schemas.
     */
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
        request: T,
    ): ToolResult
}
