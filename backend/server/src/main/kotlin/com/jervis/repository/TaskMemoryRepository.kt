package com.jervis.repository

import com.jervis.domain.agent.TaskMemoryDocument
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for TaskMemoryDocument - context storage between Qualifier and Workflow agents.
 */
@Repository
interface TaskMemoryRepository : CoroutineCrudRepository<TaskMemoryDocument, ObjectId> {
    /**
     * Find TaskMemory by correlation ID (one-to-one with PendingTask).
     */
    suspend fun findByCorrelationId(correlationId: String): TaskMemoryDocument?

    /**
     * Find all TaskMemory for a specific client.
     */
    suspend fun findByClientId(clientId: ObjectId): List<TaskMemoryDocument>

    /**
     * Find all TaskMemory for a specific project.
     */
    suspend fun findByProjectId(projectId: ObjectId): List<TaskMemoryDocument>

    /**
     * Delete TaskMemory by correlation ID (when PendingTask is deleted).
     */
    suspend fun deleteByCorrelationId(correlationId: String)

    /**
     * Find all TaskMemory with specific routing decision.
     */
    suspend fun findByRoutingDecision(routingDecision: String): List<TaskMemoryDocument>
}
