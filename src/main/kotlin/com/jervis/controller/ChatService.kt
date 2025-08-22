package com.jervis.controller

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.service.agent.coordinator.ChatCoordinator
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Service for handling chat functionality.
 */
@Service
class ChatService(
    private val coordinator: ChatCoordinator,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Process a chat query using the provided context.
     */
    suspend fun processQuery(
        text: String,
        ctx: ChatRequestContext,
    ): ChatResponse =
        try {
            logger.info {
                "Processing chat query with ctx: client=${ctx.clientName}, project=${ctx.projectName}, auto=${ctx.autoScope}"
            }
            coordinator.handle(text = text, ctx = ctx)
        } catch (e: Exception) {
            logger.error(e) { "Error processing chat query: ${e.message}" }
            ChatResponse(message = "Sorry, an error occurred while processing your query: ${e.message}")
        }
}
