package com.jervis.service

import com.jervis.module.llmcoordinator.LlmCoordinator
import com.jervis.module.ragcore.RagOrchestrator
import com.jervis.module.indexer.EmbeddingService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Service for handling chat functionality.
 * This service integrates RAG and LLM to provide responses to user queries.
 */
@Service
class ChatService(
    private val ragOrchestrator: RagOrchestrator,
    private val llmCoordinator: LlmCoordinator,
    private val embeddingService: EmbeddingService,
    private val projectService: ProjectService
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Process a chat query using a two-phase approach:
     * 1. First query to RAG
     * 2. Send result to LLM for query refinement
     * 3. Final RAG query with refined query
     * 4. LLM processing of final RAG result
     *
     * @param query The user query
     * @return The response to the query
     */
    fun processQuery(query: String): String {
        try {
            logger.info { "Processing chat query: $query" }

            // Get the active project
            val activeProject = projectService.getActiveProject()
            if (activeProject == null) {
                logger.warn { "No active project found" }
                return "No active project selected. Please select a project first."
            }

            // Phase 1: Initial RAG query
            logger.debug { "Phase 1: Initial RAG query" }
            val initialRagResponse = ragOrchestrator.processQuery(query, activeProject.id!!)

            // Phase 2: LLM query refinement
            logger.debug { "Phase 2: LLM query refinement" }
            val refinementPrompt = """
                Based on the following user query and initial context, please refine the query to better retrieve relevant information from the knowledge base.

                Original query: $query

                Initial context:
                ${initialRagResponse.context}

                Please provide a refined query that will help retrieve more relevant information.
            """.trimIndent()

            val llmRefinementResponse = llmCoordinator.processQuery(refinementPrompt, "")
            val refinedQuery = llmRefinementResponse.answer

            logger.debug { "Refined query: $refinedQuery" }

            // Phase 3: Final RAG query with refined query
            logger.debug { "Phase 3: Final RAG query with refined query" }
            val finalRagResponse = ragOrchestrator.processQuery(refinedQuery, activeProject.id!!)

            // Phase 4: LLM processing of final RAG result
            logger.debug { "Phase 4: LLM processing of final RAG result" }
            val finalPrompt = """
                Please answer the following user query based on the provided context.

                User query: $query

                Context:
                ${finalRagResponse.context}

                Please provide a comprehensive and accurate answer based only on the information in the context.
            """.trimIndent()

            val finalLlmResponse = llmCoordinator.processQuery(finalPrompt, finalRagResponse.context)

            logger.info { "Chat query processed successfully" }
            return finalLlmResponse.answer
        } catch (e: Exception) {
            logger.error(e) { "Error processing chat query: ${e.message}" }
            return "Sorry, an error occurred while processing your query: ${e.message}"
        }
    }
}
