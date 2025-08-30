package com.jervis.service.mcp.tools

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import org.springframework.stereotype.Service

/**
 * Placeholder RAG query tool. Since no RAG service exists in the project, it returns
 * a deterministic message including the current initial query. This keeps planner flows working
 * without introducing external dependencies.
 */
@Service
class RagQueryTool : McpTool {
    override val name: String = "rag.query"
    override val description: String = "Perform a placeholder RAG query (not configured) and echo the query."

    override suspend fun execute(
        context: TaskContextDocument,
        parameters: String,
    ): ToolResult {
        val suffix = if (parameters.isEmpty()) "(no query)" else parameters
        val message = "RAG placeholder: no results (service not configured). Query=\"$suffix\""
        return ToolResult.ok(message)
    }
}
