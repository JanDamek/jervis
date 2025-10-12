package com.jervis.service

import com.jervis.domain.context.TaskContext
import org.bson.types.ObjectId

interface ITaskContextService {
    suspend fun create(
        clientId: ObjectId,
        projectId: ObjectId,
        quick: Boolean = false,
        contextName: String = "New Context",
    ): TaskContext

    suspend fun save(context: TaskContext)

    suspend fun findById(contextId: ObjectId): TaskContext?

    suspend fun listFor(
        clientId: ObjectId,
        projectId: ObjectId?,
    ): List<TaskContext>

    suspend fun delete(contextId: ObjectId)
}
