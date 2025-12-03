package com.jervis.service.agent

import com.jervis.domain.agent.TaskMemoryDocument
import com.jervis.repository.TaskMemoryRepository
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * TaskMemoryService – Context storage for passing information between Qualifier and Workflow agents.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * - Stores structured context from KoogQualifierAgent (CPU)
 * - KoogWorkflowAgent (GPU) loads this context instead of re-reading raw documents
 * - Enables efficient context passing without duplicating work
 * - Persists for audit trail
 */
@Service
class TaskMemoryService(
    private val repository: TaskMemoryRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Create or update TaskMemory for a PendingTask.
     * Called by KoogQualifierAgent after structuring data.
     */
    suspend fun saveTaskMemory(
        correlationId: String,
        clientId: ObjectId,
        projectId: ObjectId?,
        contextSummary: String,
        graphNodeKeys: List<String>,
        ragDocumentIds: List<String>,
        structuredData: Map<String, String>,
        routingDecision: String,
        routingReason: String,
        sourceType: String? = null,
        sourceId: String? = null,
    ): TaskMemoryDocument {
        require(correlationId.isNotBlank()) { "Correlation ID must not be blank" }
        require(contextSummary.isNotBlank()) { "Context summary must not be blank" }
        require(routingDecision in setOf("DONE", "READY_FOR_GPU")) {
            "Routing decision must be DONE or READY_FOR_GPU"
        }

        // Check if TaskMemory already exists (update scenario)
        val existing = repository.findByCorrelationId(correlationId)

        val taskMemory = TaskMemoryDocument(
            id = existing?.id ?: ObjectId(),
            correlationId = correlationId,
            clientId = clientId,
            projectId = projectId,
            contextSummary = contextSummary,
            graphNodeKeys = graphNodeKeys,
            ragDocumentIds = ragDocumentIds,
            structuredData = structuredData,
            routingDecision = routingDecision,
            routingReason = routingReason,
            sourceType = sourceType,
            sourceId = sourceId,
        )

        val saved = repository.save(taskMemory)

        logger.info {
            "TASK_MEMORY_SAVED: correlationId=$correlationId, " +
                "routing=$routingDecision, " +
                "graphNodes=${graphNodeKeys.size}, " +
                "ragDocs=${ragDocumentIds.size}, " +
                "contextLength=${contextSummary.length}"
        }

        return saved
    }

    /**
     * Load TaskMemory by correlation ID.
     * Called by KoogWorkflowAgent when processing READY_FOR_GPU tasks.
     */
    suspend fun loadTaskMemory(correlationId: String): TaskMemoryDocument? {
        val memory = repository.findByCorrelationId(correlationId)

        if (memory != null) {
            logger.info {
                "TASK_MEMORY_LOADED: correlationId=$correlationId, " +
                    "routing=${memory.routingDecision}, " +
                    "graphNodes=${memory.graphNodeKeys.size}, " +
                    "ragDocs=${memory.ragDocumentIds.size}"
            }
        } else {
            logger.warn { "TASK_MEMORY_NOT_FOUND: correlationId=$correlationId" }
        }

        return memory
    }

    /**
     * Delete TaskMemory when PendingTask is deleted.
     */
    suspend fun deleteTaskMemory(correlationId: String) {
        repository.deleteByCorrelationId(correlationId)
        logger.info { "TASK_MEMORY_DELETED: correlationId=$correlationId" }
    }

    /**
     * Get all TaskMemory for a specific client.
     * Useful for debugging or analytics.
     */
    suspend fun getClientTaskMemories(clientId: ObjectId): List<TaskMemoryDocument> {
        return repository.findByClientId(clientId)
    }

    /**
     * Get all TaskMemory for a specific project.
     */
    suspend fun getProjectTaskMemories(projectId: ObjectId): List<TaskMemoryDocument> {
        return repository.findByProjectId(projectId)
    }

    /**
     * Get all TaskMemory with specific routing decision.
     * Useful for monitoring qualification results.
     */
    suspend fun getByRoutingDecision(routingDecision: String): List<TaskMemoryDocument> {
        return repository.findByRoutingDecision(routingDecision)
    }
}
