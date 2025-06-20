package com.jervis.module.ragcore

import com.jervis.module.indexer.EmbeddingService
import com.jervis.module.llmcoordinator.LlmCoordinator
import com.jervis.module.vectordb.VectorDbService
import com.jervis.rag.Document
import org.springframework.stereotype.Service

/**
 * Service for orchestrating the RAG (Retrieval-Augmented Generation) process.
 * This service coordinates the retrieval of relevant documents and the generation of responses.
 */
@Service
class RagOrchestrator(
    private val vectorDbService: VectorDbService,
    private val contextManager: RagContextManager,
    private val embeddingService: EmbeddingService,
    private val llmCoordinator: LlmCoordinator
) {
    /**
     * Process a query using RAG
     * 
     * @param query The user query
     * @param projectId The ID of the project
     * @param options Additional options for processing
     * @return The RAG response
     */
    fun processQuery(query: String, projectId: Long, options: Map<String, Any> = emptyMap()): RagResponse {
        // 1. Generate embeddings for the query using the EmbeddingService
        val queryEmbedding = embeddingService.generateTextEmbedding(query)

        // 2. Retrieve relevant documents
        val filter = mapOf("project" to projectId)
        val retrievedDocs = vectorDbService.searchSimilar(queryEmbedding, limit = 5, filter = filter)

        // Add project ID to options for context building
        val contextOptions = options.toMutableMap()
        contextOptions["project_id"] = projectId

        // 3. Build context from retrieved documents and memory items
        val context = contextManager.buildContext(query, retrievedDocs, contextOptions)

        // 4. Generate response using LLM
        val prompt = """
            Please answer the following query based on the provided context.

            Query: $query

            Context:
            $context

            Please provide a comprehensive and accurate answer based only on the information in the context.
        """.trimIndent()

        val llmResponse = llmCoordinator.processQuery(prompt, context)

        return RagResponse(
            answer = llmResponse.answer,
            context = context,
            sources = retrievedDocs.map { doc -> 
                DocumentSource(
                    content = doc.pageContent.take(100) + "...",
                    metadata = doc.metadata
                )
            },
            finishReason = llmResponse.finishReason,
            promptTokens = llmResponse.promptTokens,
            completionTokens = llmResponse.completionTokens,
            totalTokens = llmResponse.totalTokens
        )
    }
}

/**
 * Response from the RAG process
 */
data class RagResponse(
    val answer: String,
    val context: String,
    val sources: List<DocumentSource> = emptyList(),
    val finishReason: String = "stop",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

/**
 * Source document used in a RAG response
 */
data class DocumentSource(
    val content: String,
    val metadata: Map<String, Any>
)
