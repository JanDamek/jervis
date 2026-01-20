package com.jervis.service.agent.coordinator

import com.jervis.dto.ChatResponse
import com.jervis.entity.TaskDocument
import com.jervis.orchestrator.OrchestratorAgent
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * KoogWorkflowService – runs the OrchestratorAgent with A2A coding delegation.
 *
 * Pipeline:
 *  - Planning phase: getContext() → createPlan() → gatherEvidence()
 *  - Execution phase: execute plan steps with A2A delegation (Aider/OpenHands)
 *  - Review phase: reviewCompleteness() with iteration support
 *  - Compose phase: build final answer
 */
@Service
class KoogWorkflowService(
    private val orchestratorAgent: OrchestratorAgent,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Run the orchestrator workflow with user input and return a finalized ChatResponse.
     *
     * @param onProgress Callback for sending progress updates to client
     */
    suspend fun run(
        task: TaskDocument,
        userInput: String,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ): ChatResponse {
        logger.info { "ORCHESTRATOR_WORKFLOW_START: correlationId=${task.correlationId}" }
        val output: String = orchestratorAgent.run(task, userInput, onProgress)
        logger.info { "ORCHESTRATOR_WORKFLOW_COMPLETE: correlationId=${task.correlationId}" }
        return ChatResponse(output)
    }
}
