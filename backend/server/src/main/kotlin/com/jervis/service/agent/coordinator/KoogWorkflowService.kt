package com.jervis.service.agent.coordinator

import com.jervis.dto.ChatResponse
import com.jervis.entity.PendingTaskDocument
import com.jervis.graphdb.GraphDBService
import com.jervis.koog.KoogWorkflowAgent
import com.jervis.service.text.TikaTextExtractionService
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * KoogWorkflowService – runs the Koog complex workflow agent in parallel to AgentOrchestratorService.
 *
 * Pipeline:
 *  - Pre‑processing happens inside KoogWorkflowAgent (RAG and Graph enrichment)
 *  - Koog agent executes tools via KoogBridgeTools
 *  - Finalizer translates the final answer into the original input language and produces ChatResponse
 */
@Service
class KoogWorkflowService(
    private val graphDBService: GraphDBService,
    private val koogWorkflowAgent: KoogWorkflowAgent,
    private val tikaTextExtractionService: TikaTextExtractionService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Run Koog workflow with user input and return a finalized ChatResponse
     * translated to the task.originalLanguage.
     */
    suspend fun run(
        task: PendingTaskDocument,
        userInput: String,
    ): ChatResponse {
        logger.info { "KOOG_WORKFLOW_START: correlationId=${task.correlationId}" }

        // Safety check: if content still has many HTML tags, clean it through Tika
        val cleanedTask = tikaTextExtractionService.ensureCleanContent(task)

        val output: String = koogWorkflowAgent.run(cleanedTask, graphDBService, userInput)

        logger.info { "KOOG_WORKFLOW_COMPLETE: correlationId=${task.correlationId}" }
        return ChatResponse(output)
    }
}
