package com.jervis.service.mcp.tools

import com.jervis.domain.agent.TaskStatus
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.agent.AgentConstants
import com.jervis.service.mcp.McpAction
import com.jervis.service.mcp.McpTool
import org.bson.types.ObjectId
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

    override val action: McpAction = McpAction(
        type = "user",
        content = "await",
        parameters = emptyMap(),
    )

    override suspend fun execute(action: String, contextId: ObjectId): String {
        val ctx = taskContextRepo.findByContextId(contextId) ?: return "No task context found"
        val wm = ctx.workingMemory.toMutableMap()
        wm["awaitingUser"] = true
        val updated = ctx.copy(status = TaskStatus.AWAITING_USER, workingMemory = wm)
        taskContextRepo.save(updated)
        return "Awaiting user input"
    }
}
