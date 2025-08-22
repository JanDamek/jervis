package com.jervis.service.mcp.tools

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.agent.AgentConstants
import com.jervis.service.mcp.McpAction
import com.jervis.service.mcp.McpTool
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Simple tool that echoes the initialQuery stored in TaskContextDocument.
 * Useful as a deterministic first step to exercise the planning/execution loop.
 */
@Service
class ContextEchoTool(
    private val taskContextRepo: TaskContextMongoRepository,
) : McpTool {
    override val name: String = AgentConstants.DefaultSteps.CONTEXT_ECHO

    override val action: McpAction = McpAction(
        type = "context",
        content = "echo",
        parameters = emptyMap(),
    )

    override suspend fun execute(action: String, contextId: ObjectId): String {
        val ctx: TaskContextDocument? = taskContextRepo.findByContextId(contextId)
        return ctx?.initialQuery ?: ""
    }
}
