package com.jervis.service.agent.coordinator

import com.jervis.dto.ChatResponse
import com.jervis.entity.TaskDocument
import com.jervis.koog.KoogWorkflowAgent
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
    private val koogWorkflowAgent: KoogWorkflowAgent,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Run Koog workflow with user input and return a finalized ChatResponse
     * translated to the task.originalLanguage.
     */
    suspend fun run(
        task: TaskDocument,
        userInput: String,
    ): ChatResponse {
        logger.info { "KOOG_WORKFLOW_START: correlationId=${task.correlationId}" }
        val output: String = koogWorkflowAgent.run(task, userInput)
        logger.info { "KOOG_WORKFLOW_COMPLETE: correlationId=${task.correlationId}" }
        return ChatResponse(output)
    }
}
