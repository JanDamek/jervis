package com.jervis.module.mcp

import com.jervis.module.llmcoordinator.LlmCoordinator
import com.jervis.module.llmcoordinator.LlmResponse
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Coordinator that integrates the Model Context Protocol (MCP) with the LLM coordinator.
 * This service wraps the LlmCoordinator and adds MCP capabilities.
 */
@Service
class McpLlmCoordinator(
    private val llmCoordinator: LlmCoordinator,
    private val koogMcpService: KoogMcpService
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Process a query with MCP capabilities
     *
     * @param query The user query
     * @param context The context for the query
     * @param options Additional options for processing
     * @return The LLM response with MCP capabilities
     */
    suspend fun processQuery(
        query: String,
        context: String,
        options: Map<String, Any> = emptyMap()
    ): LlmResponse {
        logger.info { "Processing query with MCP-enabled LLM coordinator: ${query.take(50)}..." }

        // Check if MCP is enabled for this query
        val useMcp = options["use_mcp"] as? Boolean ?: true

        // Check if external model providers should be used
        val useExternalProviders = options["use_external_providers"] as? Boolean ?: false

        // Create a new options map with the external providers flag if needed
        val updatedOptions = if (useExternalProviders) {
            options + ("use_external_providers" to true)
        } else {
            options
        }

        return if (useMcp) {
            // Process the query with MCP
            val mcpResponse = koogMcpService.processQuery(query, context, updatedOptions)

            // Convert MCP response to LLM response
            LlmResponse(
                answer = mcpResponse.answer,
                model = mcpResponse.model,
                promptTokens = mcpResponse.promptTokens,
                completionTokens = mcpResponse.completionTokens,
                totalTokens = mcpResponse.totalTokens,
                finishReason = "stop"
            )
        } else {
            // Process the query without MCP
            llmCoordinator.processQuery(query, context, updatedOptions)
        }
    }

    /**
     * Process a query with MCP capabilities and return the full MCP response
     *
     * @param query The user query
     * @param context The context for the query
     * @param options Additional options for processing
     * @return The full MCP response
     */
    suspend fun processQueryWithMcp(
        query: String,
        context: String,
        options: Map<String, Any> = emptyMap()
    ): McpResponse {
        logger.info { "Processing query with full MCP capabilities: ${query.take(50)}..." }

        // Check if external model providers should be used
        val useExternalProviders = options["use_external_providers"] as? Boolean ?: false

        // Create a new options map with the external providers flag if needed
        val updatedOptions = if (useExternalProviders) {
            options + ("use_external_providers" to true)
        } else {
            options
        }

        return koogMcpService.processQuery(query, context, updatedOptions)
    }
}
