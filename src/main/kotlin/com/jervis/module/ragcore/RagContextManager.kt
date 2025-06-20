package com.jervis.module.ragcore

import com.jervis.entity.Project
import com.jervis.module.memory.MemoryItemType
import com.jervis.module.memory.MemoryDocument
import com.jervis.rag.Document
import com.jervis.module.memory.MemoryService
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Service for managing context in the RAG process.
 * This service builds context from retrieved documents and memory items for use in LLM prompts.
 */
@Service
class RagContextManager(
    private val memoryService: MemoryService
) {

    /**
     * Build context from retrieved documents
     * 
     * @param query The user query
     * @param documents The retrieved documents
     * @param options Additional options for context building
     * @return The built context as a string
     */
    fun buildContext(query: String, documents: List<Document>, options: Map<String, Any> = emptyMap()): String {
        val maxTokens = options["max_tokens"] as? Int ?: 4000
        val projectId = options["project_id"] as? Long
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
        documents.forEachIndexed { index, doc ->
            val source = doc.metadata["file_path"] as? String ?: "Document $index"
            contextBuilder.append("Source: $source\n")
            contextBuilder.append("Content: ${doc.pageContent}\n\n")
        }

        // In a real implementation, we would truncate the context to fit within maxTokens
        // For now, we'll just return the built context
        return contextBuilder.toString()
    }

    /**
     * Rerank documents based on relevance to the query
     * 
     * @param query The user query
     * @param documents The retrieved documents
     * @return The reranked documents
     */
    fun rerankDocuments(query: String, documents: List<Document>): List<Document> {
        // In a real implementation, this would use a reranking model
        // For now, we'll just return the documents in the original order
        return documents
    }

    /**
     * Merge similar documents to reduce redundancy
     * 
     * @param documents The documents to merge
     * @return The merged documents
     */
    fun mergeDocuments(documents: List<Document>): List<Document> {
        // In a real implementation, this would merge similar documents
        // For now, we'll just return the original documents
        return documents
    }

    /**
     * Add relevant memory items to the context.
     * 
     * @param contextBuilder The StringBuilder to add memory items to
     * @param projectId The ID of the project to get memory items for
     * @param limit The maximum number of memory items to add
     */
    private fun addMemoryItemsToContext(contextBuilder: StringBuilder, projectId: Long, limit: Int) {
        try {
            val project = Project(id = projectId, name = "", path = "")

            // Get important memory items (decisions, plans)
            val decisions = memoryService.getMemoryItemsByType(project, MemoryItemType.DECISION)
                .sortedByDescending { MemoryDocument.getImportance(it) }
                .take(limit)

            val plans = memoryService.getMemoryItemsByType(project, MemoryItemType.PLAN)
                .sortedByDescending { MemoryDocument.getImportance(it) }
                .take(limit)

            // Get recent meeting transcripts
            val meetings = memoryService.getMemoryItemsByType(project, MemoryItemType.MEETING_TRANSCRIPT)
                .sortedByDescending { MemoryDocument.getCreatedAt(it) }
                .take(limit)

            // Add decisions to context
            if (decisions.isNotEmpty()) {
                contextBuilder.append("PROJECT DECISIONS:\n")
                decisions.forEach { decision ->
                    contextBuilder.append("Decision: ${MemoryDocument.getTitle(decision)}\n")
                    contextBuilder.append("${decision.pageContent}\n\n")
                }
            }

            // Add plans to context
            if (plans.isNotEmpty()) {
                contextBuilder.append("PROJECT PLANS:\n")
                plans.forEach { plan ->
                    contextBuilder.append("Plan: ${MemoryDocument.getTitle(plan)}\n")
                    contextBuilder.append("${plan.pageContent}\n\n")
                }
            }

            // Add meeting transcripts to context
            if (meetings.isNotEmpty()) {
                contextBuilder.append("RECENT MEETINGS:\n")
                meetings.forEach { meeting ->
                    contextBuilder.append("Meeting: ${MemoryDocument.getTitle(meeting)} (${MemoryDocument.getCreatedAt(meeting)})\n")
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
