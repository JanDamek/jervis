package com.jervis.service.agent

import com.jervis.entity.mongo.ClientDocument
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Centralized resolution of client/project scope based on optional name hints.
 * Returns matched ids and normalized names along with warnings.
 */
@Service
class ScopeResolutionService(
    private val clientService: ClientService,
    private val projectService: ProjectService,
) {
    data class ScopeResolutionResult(
        val clientName: String?,
        val projectName: String?,
        val clientId: ObjectId?,
        val projectId: ObjectId?,
        val warnings: List<String> = emptyList(),
    )

    suspend fun resolve(
        clientHint: String?,
        projectHint: String?,
    ): ScopeResolutionResult {
        val clients: List<ClientDocument> = clientService.list()
        val projects: List<ProjectDocument> = projectService.getAllProjects()

        val matchedClient = clientHint?.let { name -> clients.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val matchedProject = projectHint?.let { name -> projects.firstOrNull { it.name.equals(name, ignoreCase = true) } }

        val warnings = mutableListOf<String>()
        if (projectHint?.isNotBlank() == true && matchedProject == null) {
            warnings += "Warning: project '$projectHint' not found"
        }
        if (matchedClient != null && matchedProject != null && matchedProject.clientId != matchedClient.id) {
            warnings += "Warning: project '${matchedProject.name}' does not belong to client '${matchedClient.name}'"
        }

        return ScopeResolutionResult(
            clientName = matchedClient?.name ?: clientHint,
            projectName = matchedProject?.name ?: projectHint,
            clientId = matchedClient?.id,
            projectId = matchedProject?.id,
            warnings = warnings,
        )
    }
}
