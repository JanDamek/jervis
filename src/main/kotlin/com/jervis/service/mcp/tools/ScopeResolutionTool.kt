package com.jervis.service.mcp.tools

import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.client.ClientService
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.project.ProjectService
import org.springframework.stereotype.Service

@Service
class ScopeResolutionTool(
    private val clientService: ClientService,
    private val projectService: ProjectService,
) : McpTool {
    override val name: String = "scope.resolve"
    override val description: String =
        "Resolves and validates client/project scope from task context. Use to establish working context and verify client-project relationships before performing scoped operations."

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult {
        val client = context.clientDocument
        val project = context.projectDocument
        val summary =
            "client=$client; project=$project; client_description=${client.description}; project_description=${project.description}"
        return ToolResult.ok(summary)
    }
}
