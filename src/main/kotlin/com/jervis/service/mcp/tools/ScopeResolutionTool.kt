package com.jervis.service.mcp.tools

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.service.agent.AgentConstants
import com.jervis.service.client.ClientService
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.ToolResult
import com.jervis.service.project.ProjectService
import org.springframework.stereotype.Service

@Service
class ScopeResolutionTool(
    private val clientService: ClientService,
    private val projectService: ProjectService,
) : McpTool {
    override val name: String = AgentConstants.DefaultSteps.SCOPE_RESOLVE
    override val description: String = "Resolve client/project scope using hints from the task context."

    override suspend fun execute(
        context: TaskContextDocument,
        parameters: Map<String, Any>,
    ): ToolResult {
        val clients = clientService.list()
        val projects = projectService.getAllProjects()

        val clientHint = context.clientName
        val projectHint = context.projectName

        val matchedClient = clientHint?.let { name -> clients.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val matchedProject =
            projectHint?.let { name -> projects.firstOrNull { it.name.equals(name, ignoreCase = true) } }

        val warnings = mutableListOf<String>()
        if (projectHint?.isNotBlank() == true && matchedProject == null) {
            warnings += "Warning: project '$projectHint' not found"
        }
        if (matchedClient != null && matchedProject != null && matchedProject.clientId != matchedClient.id) {
            warnings += "Warning: project '${matchedProject.name}' does not belong to client '${matchedClient.name}'"
        }

        val client = matchedClient?.name ?: clientHint ?: "unknown"
        val project = matchedProject?.name ?: projectHint ?: "unknown"
        val warningsSuffix = if (warnings.isEmpty()) "" else "; warnings=" + warnings.joinToString(" | ")
        val summary = "client=$client; project=$project$warningsSuffix"
        return ToolResult(success = true, output = summary)
    }
}
