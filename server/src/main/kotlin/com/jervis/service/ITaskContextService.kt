package com.jervis.service

import com.jervis.domain.context.TaskContext
import org.bson.types.ObjectId

/**
 * Service for managing task contexts.
 * This interface is server-specific and uses server-only domain classes.
 */
interface ITaskContextService {
    /**
     * Creates a new task context for a client and project
     */
    suspend fun create(
        clientId: ObjectId,
        projectId: ObjectId,
        quick: Boolean = false,
        contextName: String = "New Context",
    ): TaskContext

    /**
     * Saves or updates a task context
     */
    suspend fun save(context: TaskContext)

    /**
     * Finds a task context by its ID
     */
    suspend fun findById(contextId: ObjectId): TaskContext?

    /**
     * Lists task contexts for a client, optionally filtered by project
     */
    suspend fun listFor(
        clientId: ObjectId,
        projectId: ObjectId?,
    ): List<TaskContext>

    /**
     * Deletes a task context
     */
    suspend fun delete(contextId: ObjectId)
}
