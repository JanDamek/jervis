package com.jervis.service.agent.context

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.repository.mongo.TaskContextMongoRepository
import com.jervis.service.agent.ScopeResolutionService
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class TaskContextService(
    private val taskContextRepo: TaskContextMongoRepository,
    private val scopeResolver: ScopeResolutionService,
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
        val resolved = scopeResolver.resolve(clientName, projectName)

        val toSave = TaskContextDocument(
            contextId = contextId,
            clientId = resolved.clientId,
            projectId = resolved.projectId,
            clientName = resolved.clientName,
            projectName = resolved.projectName,
            initialQuery = initialQuery,
        )
        return taskContextRepo.save(toSave)
    }
}