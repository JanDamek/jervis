package com.jervis.service.agent.context

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class TaskContextService(
    private val taskContextRepo: TaskContextMongoRepository,
    private val clientService: ClientService,
    private val projectService: ProjectService,
) {
    /**
     * Create a TaskContextDocument for the given ContextDocument id.
     * It stores the resolved client/project (ids and names) and the initial query (preferably English text).
     */
    suspend fun create(
        contextId: ObjectId,
        clientName: String?,
        projectName: String?,
        initialQuery: String,
    ): TaskContextDocument {
        val clients = clientService.list()
        val projects = projectService.getAllProjects()
        val client = clientName?.let { name -> clients.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val project = projectName?.let { name -> projects.firstOrNull { it.name.equals(name, ignoreCase = true) } }

        val toSave = TaskContextDocument(
            contextId = contextId,
            clientId = client?.id,
            projectId = project?.id,
            clientName = clientName,
            projectName = projectName,
            initialQuery = initialQuery,
        )
        return taskContextRepo.save(toSave)
    }
}