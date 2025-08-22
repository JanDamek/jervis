package com.jervis.service.mcp.tools

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.service.agent.AgentConstants
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.ToolResult
import org.springframework.stereotype.Service

/**
 * Placeholder RAG query tool. Since no RAG service exists in the project, it returns
 * a deterministic message including the current initial query. This keeps planner flows working
 * without introducing external dependencies.
 */
@Service
class RagQueryTool : McpTool {
    override val name: String = AgentConstants.DefaultSteps.RAG_QUERY
    override val description: String = "Perform a placeholder RAG query (not configured) and echo the query."

    override suspend fun execute(context: TaskContextDocument, parameters: Map<String, Any>): ToolResult {
        val query = context.initialQuery.trim()
        val suffix = if (query.isEmpty()) "(no query)" else query
        val message = "RAG placeholder: no results (service not configured). Query=\"$suffix\""
        return ToolResult(success = true, output = message)
    }
}
