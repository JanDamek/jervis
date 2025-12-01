package com.jervis.service.agent.coordinator

import com.jervis.domain.plan.Plan
import com.jervis.dto.ChatResponse
import com.jervis.graphdb.GraphDBService
import com.jervis.koog.KoogWorkflowAgent
import com.jervis.mcp.McpToolRegistry
import com.jervis.service.agent.finalizer.Finalizer
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * KoogWorkflowService – runs the Koog complex workflow agent in parallel to AgentOrchestratorService.
 *
 * Pipeline:
 *  - Pre‑processing happens inside KoogWorkflowAgent (RAG + Graph enrichment)
 *  - Koog agent executes tools via KoogBridgeTools
 *  - Finalizer translates final answer into the original input language and produces ChatResponse
 */
@Service
class KoogWorkflowService(
    private val mcpRegistry: McpToolRegistry,
    private val graphDBService: GraphDBService,
    private val finalizer: Finalizer,
    private val koogWorkflowAgent: KoogWorkflowAgent,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Run Koog workflow with user input and return a finalized ChatResponse
     * translated to the plan.originalLanguage.
     */
    suspend fun run(plan: Plan, userInput: String): ChatResponse {
        logger.info { "KOOG_WORKFLOW_START: planId=${'$'}{plan.id} correlationId=${'$'}{plan.correlationId}" }

        val output: String = koogWorkflowAgent.run(plan, mcpRegistry, graphDBService, userInput)

        // Save raw Koog output to plan and use the standard Finalizer that handles
        // translation to the original input language and response formatting.
        plan.finalAnswer = output

        val response = finalizer.finalize(plan)
        logger.info { "KOOG_WORKFLOW_COMPLETE: planId=${'$'}{plan.id} correlationId=${'$'}{plan.correlationId}" }
        return response
    }
}
