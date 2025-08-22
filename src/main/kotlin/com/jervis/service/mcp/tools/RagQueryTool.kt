package com.jervis.service.mcp.tools

import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.agent.AgentConstants
import com.jervis.service.mcp.McpAction
import com.jervis.service.mcp.McpTool
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Placeholder RAG query tool. Since no RAG service exists in the project, it returns
 * a deterministic message including the current initial query. This keeps planner flows working
 * without introducing external dependencies.
 */
@Service
class RagQueryTool(
    private val taskContextRepo: TaskContextMongoRepository,
) : McpTool {
    override val name: String = AgentConstants.DefaultSteps.RAG_QUERY

    override val action: McpAction = McpAction(
        type = "rag",
        content = "query",
        parameters = emptyMap(),
    )

    override suspend fun execute(action: String, contextId: ObjectId): String {
        val ctx = taskContextRepo.findByContextId(contextId)
        val query = ctx?.initialQuery?.trim().orEmpty()
        val suffix = if (query.isEmpty()) "(no query)" else query
        return "RAG placeholder: no results (service not configured). Query=\"$suffix\""
    }
}
