package com.jervis.service.mcp

import org.bson.types.ObjectId

/**
 * Base interface for MCP Tools (actions the planner can schedule in a plan).
 * Only the API is defined now; implementations will be provided later.
 */
interface McpTool {
    val name: String
    val action: McpAction

    /**
     * Execute the tool with provided action and current context id.
     * Returns textual output to be stored in the context/plan step.
     */
    suspend fun execute(
        action: String,
        contextId: ObjectId,
    ): String
}
