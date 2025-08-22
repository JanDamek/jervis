package com.jervis.service.mcp.tools

import com.jervis.domain.agent.TaskStatus
import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.agent.AgentConstants
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.ToolResult
import org.springframework.stereotype.Service

/**
 * Tool that marks the current task as awaiting user input.
 * It updates TaskContext.status to AWAITING_USER and stores a hint in workingMemory.
 */
@Service
class UserInteractionTool(
    private val taskContextRepo: TaskContextMongoRepository,
) : McpTool {
    override val name: String = AgentConstants.DefaultSteps.USER_AWAIT
    override val description: String = "Mark task as awaiting user input and persist the state."

    override suspend fun execute(context: TaskContextDocument, parameters: Map<String, Any>): ToolResult {
        val wm = context.workingMemory.toMutableMap()
        wm["awaitingUser"] = true
        val updated = context.copy(status = TaskStatus.AWAITING_USER, workingMemory = wm)
        taskContextRepo.save(updated)
        return ToolResult(success = true, output = "Awaiting user input")
    }
}
