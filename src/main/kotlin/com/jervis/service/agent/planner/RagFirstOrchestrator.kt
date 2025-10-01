package com.jervis.service.agent.planner

import com.jervis.domain.context.TaskContext
import com.jervis.domain.plan.Plan
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.tools.KnowledgeSearchTool
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Serializable
data class QueryResponse(
    val queries: List<QueryItem> = emptyList(),
)

@Serializable
data class QueryItem(
    val text: String = "",
)

/**
 * Enhanced RagFirstOrchestrator implementing DiscoveryService interface.
 * Uses functional approach with immutable operations and proper error handling.
 */
@Service
class RagFirstOrchestrator(
    private val llmGateway: LlmGateway,
    private val knowledgeSearchTool: KnowledgeSearchTool,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Performs RAG-first discovery to gather context for planning.
     * Returns Result type for consistent error handling across the planning system.
     */
    suspend fun discoverContext(
        context: TaskContext,
        plan: Plan,
    ): String {
        logger.info { "Starting RAG-first discovery for plan ${plan.id}" }

        return knowledgeSearchTool
            .execute(
                context,
                plan,
                plan.investigationGuidance.joinToString { "$it, " },
            ).output
    }
}
