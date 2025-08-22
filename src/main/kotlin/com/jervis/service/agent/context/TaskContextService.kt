package com.jervis.service.agent.context

import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.repository.mongo.TaskContextMongoRepository
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class TaskContextService(
    private val taskContextRepo: TaskContextMongoRepository,
) {
    /**
     * Create a TaskContextDocument for the given contextId.
     * This service only persists and retrieves TaskContextDocument without any scope resolution.
     */
    suspend fun create(
        contextId: ObjectId,
        clientName: String?,
        projectName: String?,
        initialQuery: String,
    ): TaskContextDocument {
        val toSave = TaskContextDocument(
            contextId = contextId,
            clientId = null,
            projectId = null,
            clientName = clientName,
            projectName = projectName,
            initialQuery = initialQuery,
        )
        return taskContextRepo.save(toSave)
    }
}