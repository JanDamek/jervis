package com.jervis.service.agent

import com.jervis.domain.agent.TaskStatus
import com.jervis.entity.mongo.TaskContextDocument
import com.jervis.repository.mongo.TaskContextMongoRepository
import kotlinx.coroutines.flow.firstOrNull
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service that provides persistence access for agent task contexts.
 * It focuses on retrieving or creating the active context and saving updates.
 */
@Service("agentTaskContextService")
class TaskContextService(
    private val repository: TaskContextMongoRepository,
) {
    /**
     * Retrieves the last active context for a client/project combination,
     * or creates a new one if none exists.
     */
    suspend fun getOrCreateActiveContext(
        clientId: ObjectId,
        projectId: ObjectId,
        initialQuery: String,
    ): TaskContextDocument {
        val activeStatuses = listOf(TaskStatus.PLANNING, TaskStatus.EXECUTING, TaskStatus.AWAITING_USER)
        val activeContext = repository
            .findByClientIdAndProjectIdAndStatusIn(clientId, projectId, activeStatuses)
            .firstOrNull()

        return activeContext ?: createNewContext(clientId, projectId, initialQuery)
    }

    private suspend fun createNewContext(
        clientId: ObjectId,
        projectId: ObjectId,
        initialQuery: String,
    ): TaskContextDocument {
        val newContext = TaskContextDocument(
            contextId = ObjectId.get(),
            clientId = clientId,
            projectId = projectId,
            initialQuery = initialQuery,
            status = TaskStatus.PLANNING,
        )
        return repository.save(newContext)
    }

    /**
     * Saves any changes to the context back to the database.
     */
    suspend fun saveContext(context: TaskContextDocument): TaskContextDocument {
        val updatedContext = context.copy(updatedAt = Instant.now())
        return repository.save(updatedContext)
    }
}
