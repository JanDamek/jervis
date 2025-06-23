package com.jervis.service

import com.jervis.module.llmcoordinator.LlmCoordinator
import com.jervis.module.ragcore.RagOrchestrator
import com.jervis.module.indexer.EmbeddingService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Base service for handling RAG (Retrieval-Augmented Generation) queries.
 * This service provides common functionality for processing queries using RAG and LLM.
 */
@Service
class RagQueryService(
    private val ragOrchestrator: RagOrchestrator,
    private val llmCoordinator: LlmCoordinator,
    private val embeddingService: EmbeddingService,
    private val projectService: ProjectService
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Process a query using a four-phase approach:
     * 1. First query to RAG
     * 2. Send result to LLM for query refinement
     * 3. Final RAG query with refined query
     * 4. LLM processing of final RAG result
     *
     * @param query The user query
     * @param projectId The ID of the project to use, or null to use the active project
     * @return The response to the query with additional metadata
     * @throws IllegalArgumentException if no project is found
     */
    fun processRagQuery(query: String, projectId: Long? = null): RagQueryResult {
        logger.info { "Processing RAG query: $query" }

        // Get the project ID to use
        val resolvedProjectId = projectId ?: resolveProjectId()

        // Phase 1: Initial RAG query
        logger.debug { "Phase 1: Initial RAG query" }
        val initialRagResponse = ragOrchestrator.processQuery(query, resolvedProjectId)

        // Phase 2: LLM query refinement
        logger.debug { "Phase 2: LLM query refinement" }
        val refinementPrompt = """
            Based on the following user query and initial context, please refine the query to better retrieve relevant information from the knowledge base.

            Original query: $query

            Initial context:
            ${initialRagResponse.context}

            Please provide a refined query that will help retrieve more relevant information.
        """.trimIndent()

        val llmRefinementResponse = llmCoordinator.processQueryBlocking(refinementPrompt, "")
        val refinedQuery = llmRefinementResponse.answer

        logger.debug { "Refined query: $refinedQuery" }

        // Phase 3: Final RAG query with refined query
        logger.debug { "Phase 3: Final RAG query with refined query" }
        val finalRagResponse = ragOrchestrator.processQuery(refinedQuery, resolvedProjectId)

        // Phase 4: LLM processing of final RAG result
        logger.debug { "Phase 4: LLM processing of final RAG result" }
        val finalPrompt = """
            Please answer the following user query based on the provided context.

            User query: $query

            Context:
            ${finalRagResponse.context}

            Please provide a comprehensive and accurate answer based only on the information in the context.
        """.trimIndent()

        val finalLlmResponse = llmCoordinator.processQueryBlocking(finalPrompt, finalRagResponse.context)

        logger.info { "RAG query processed successfully" }
        return RagQueryResult(
            answer = finalLlmResponse.answer,
            context = finalRagResponse.context,
            sources = finalRagResponse.sources,
            finishReason = finalLlmResponse.finishReason,
            promptTokens = finalLlmResponse.promptTokens,
            completionTokens = finalLlmResponse.completionTokens,
            totalTokens = finalLlmResponse.totalTokens
        )
    }

    /**
     * Resolves the project ID to use for the query.
     * 
     * @return The project ID to use
     * @throws IllegalArgumentException if no project is found
     */
    private fun resolveProjectId(): Long {
        // Get the active project
        val activeProject = projectService.getActiveProjectBlocking()
        if (activeProject == null) {
            logger.warn { "No active project found" }
            throw IllegalArgumentException("No active project selected. Please select a project first.")
        }

        return activeProject.id ?: throw IllegalArgumentException("Active project has no ID")
    }
}

/**
 * Result of a RAG query processing
 */
data class RagQueryResult(
    val answer: String,
    val context: String,
    val sources: List<Any> = emptyList(),
    val finishReason: String = "stop",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)
