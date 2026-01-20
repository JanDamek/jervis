package com.jervis.service.agent.coordinator

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.service.text.CzechKeyboardNormalizer
import com.jervis.types.SourceUrn
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * AgentOrchestratorService - a simplified orchestrator that runs KoogWorkflowService.
 *
 * This replaces the old complex planner/executor architecture with direct Koog agent execution.
 * No more MCP tools, no more multiphase planning - just straight Koog workflow.
 */
@Service
class AgentOrchestratorService(
    private val koogWorkflowService: KoogWorkflowService,
    private val czechKeyboardNormalizer: CzechKeyboardNormalizer,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Handle incoming chat request by creating a TaskContext and running Koog workflow.
     * This is the main entry point used by controllers.
     *
     * @param onProgress Callback for sending progress updates to client
     */
    suspend fun handle(
        text: String,
        ctx: ChatRequestContext,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ): ChatResponse {
        val normalizedText = czechKeyboardNormalizer.convertIfMistyped(text)
        if (normalizedText != text) {
            logger.info { "AGENT_INPUT_NORMALIZED: original='${text.take(100)}' normalized='${normalizedText.take(100)}'" }
        }
        logger.info { "AGENT_HANDLE_START: text='${normalizedText.take(100)}' clientId=${ctx.clientId} projectId=${ctx.projectId}" }

        val taskContext =
            TaskDocument(
                type = TaskTypeEnum.USER_INPUT_PROCESSING,
                content = normalizedText,
                projectId = ctx.projectId,
                clientId = ctx.clientId,
                correlationId = ObjectId().toString(),
                sourceUrn = SourceUrn.chat(ctx.clientId),
            )

        // Run Koog workflow with progress callback
        val response = run(taskContext, normalizedText, onProgress)

        logger.info { "AGENT_HANDLE_COMPLETE: correlationId=${taskContext.correlationId}" }
        return response
    }

    /**
     * Run agent workflow for the given taskContext and user input.
     * Simply delegates to KoogWorkflowService, which runs the Koog agent.
     *
     * @param onProgress Callback for sending progress updates to client
     */
    suspend fun run(
        task: TaskDocument,
        userInput: String,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ): ChatResponse {
        logger.info { "AGENT_ORCHESTRATOR_START: correlationId=${task.correlationId}" }

        val response = koogWorkflowService.run(task, userInput, onProgress)

        logger.info { "AGENT_ORCHESTRATOR_COMPLETE: correlationId=${task.correlationId}" }
        return response
    }
}
