package com.jervis.service.rag

import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.indexer.EmbeddingService
import com.jervis.service.vectordb.VectorDbService
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Service for managing context in the RAG process.
 * This service builds context from retrieved documents and memory items for use in LLM prompts.
 */
@Service
class RagContextManager(
    private val vectorDbService: VectorDbService,
    private val embeddingService: EmbeddingService,
) {
    /**
     * Get memory items by type for a specific project
     *
     * @param projectId The ID of the project
     * @param documentType The type of documents to retrieve
     * @param limit The maximum number of documents to retrieve
     * @return List of RagDocuments matching the criteria
     */
    private suspend fun getMemoryItemsByType(
        projectId: ObjectId,
        documentType: RagDocumentType,
        limit: Int,
    ): List<RagDocument> {
        return try {
            val filter = mapOf(
                "project" to projectId,
                "documentType" to documentType.toString()
            )
            // Use a dummy query embedding since we're filtering by type, not semantic similarity
            val dummyEmbedding = List(1536) { 0.0f } // Common embedding size
            vectorDbService.searchSimilar(dummyEmbedding, limit = limit * 2, filter = filter)
                .filter { it.documentType == documentType }
                .sortedByDescending { it.createdAt }
                .take(limit)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Search memory items by query for a specific project
     *
     * @param project The project document
     * @param query The search query
     * @param limit The maximum number of documents to retrieve
     * @return List of RagDocuments matching the query
     */
    suspend fun searchMemoryItems(
        project: ProjectDocument,
        query: String,
        limit: Int,
    ): List<RagDocument> {
        return try {
            val queryEmbedding = embeddingService.generateQueryEmbedding(query)
            val filter = mapOf("project" to project.id)
            vectorDbService.searchSimilar(queryEmbedding, limit = limit, filter = filter)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Build context from retrieved documents
     *
     * @param query The user query
     * @param ragDocuments The retrieved documents
     * @param options Additional options for context building
     * @return The built context as a string
     */
    suspend fun buildContext(
        query: String,
        ragDocuments: List<RagDocument>,
        options: Map<String, Any> = emptyMap(),
    ): String {
        options["max_tokens"] as? Int ?: 4000
        val projectId = options["project_id"] as? ObjectId
        val includeMemory = options["include_memory"] as? Boolean ?: true
        val memoryLimit = options["memory_limit"] as? Int ?: 3
        val contextBuilder = StringBuilder()

        // Add a header to the context
        contextBuilder.append("Context information for query: $query\n\n")

        // Add memory items to the context if requested
        if (includeMemory && projectId != null) {
            addMemoryItemsToContext(contextBuilder, projectId, memoryLimit)
        }

        // Add each document to the context
        ragDocuments.forEachIndexed { index, doc ->
            val source = "Document ${index + 1}"
            contextBuilder.append("Source: $source\n")
            contextBuilder.append("Content: ${doc.pageContent}\n\n")
        }

        // In a real implementation, we would truncate the context to fit within maxTokens
        // For now, we'll just return the built context
        return contextBuilder.toString()
    }

    /**
     * Add relevant memory items to the context.
     *
     * @param contextBuilder The StringBuilder to add memory items to
     * @param projectId The ID of the project to get memory items for
     * @param limit The maximum number of memory items to add
     */
    private suspend fun addMemoryItemsToContext(
        contextBuilder: StringBuilder,
        projectId: ObjectId,
        limit: Int,
    ) {
        try {
            // Get important memory items (decisions, plans)
            val decisions = getMemoryItemsByType(projectId, RagDocumentType.DECISION, limit)
            val plans = getMemoryItemsByType(projectId, RagDocumentType.PLAN, limit)
            val meetings = getMemoryItemsByType(projectId, RagDocumentType.MEETING, limit)

            // Add decisions to context
            if (decisions.isNotEmpty()) {
                contextBuilder.append("PROJECT DECISIONS:\n")
                decisions.forEach { decision: RagDocument ->
                    contextBuilder.append("Decision:\n")
                    contextBuilder.append("${decision.pageContent}\n\n")
                }
            }

            // Add plans to context
            if (plans.isNotEmpty()) {
                contextBuilder.append("PROJECT PLANS:\n")
                plans.forEach { plan: RagDocument ->
                    contextBuilder.append("Plan:\n")
                    contextBuilder.append("${plan.pageContent}\n\n")
                }
            }

            // Add meeting transcripts to context
            if (meetings.isNotEmpty()) {
                contextBuilder.append("RECENT MEETINGS:\n")
                meetings.forEach { meeting: RagDocument ->
                    contextBuilder.append("Meeting (${meeting.createdAt}):\n")
                    contextBuilder.append("Summary: ${meeting.pageContent.take(200)}...\n\n")
                }
            }
        } catch (e: Exception) {
            // Log error but continue - we don't want to fail the entire context building
            // if memory retrieval fails
            contextBuilder.append("Note: Could not retrieve memory items due to an error.\n\n")
        }
    }
}
