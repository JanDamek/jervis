package com.jervis.controller

import com.jervis.service.rag.RagQueryService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Service for handling chat functionality.
 * This service uses the RagQueryService to provide responses to user queries.
 */
@Service
class ChatService(
    private val ragQueryService: RagQueryService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Process a chat query using the RAG query service.
     *
     * @param query The user query
     * @return The response to the query
     */
    suspend fun processQuery(query: String): String {
        try {
            logger.info { "Processing chat query: $query" }

            val result = ragQueryService.processRagQuery(query)

            logger.info { "Chat query processed successfully" }
            return result.answer
        } catch (e: Exception) {
            logger.error(e) { "Error processing chat query: ${e.message}" }
            return "Sorry, an error occurred while processing your query: ${e.message}"
        }
    }
}
