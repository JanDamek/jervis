package com.jervis.service.agent.coordinator

import com.jervis.dto.ChatRequestContext
import com.jervis.dto.ChatResponse
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.entity.PendingTaskDocument
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
     */
    suspend fun handle(
        text: String,
        ctx: ChatRequestContext,
    ): ChatResponse {
        val normalizedText = czechKeyboardNormalizer.convertIfMistyped(text)
        if (normalizedText != text) {
            logger.info { "AGENT_INPUT_NORMALIZED: original='${text.take(100)}' normalized='${normalizedText.take(100)}'" }
        }
        logger.info { "AGENT_HANDLE_START: text='${normalizedText.take(100)}' clientId=${ctx.clientId} projectId=${ctx.projectId}" }

        val taskContext =
            PendingTaskDocument(
                type = PendingTaskTypeEnum.USER_INPUT_PROCESSING,
                content = normalizedText,
                projectId = ctx.projectId,
                clientId = ctx.clientId,
                correlationId = ObjectId().toString(),
                sourceUrn = SourceUrn.chat(ctx.clientId),
            )

        // Run Koog workflow
        val response = run(taskContext, normalizedText)

        logger.info { "AGENT_HANDLE_COMPLETE: correlationId=${taskContext.correlationId}" }
        return response
    }

    /**
     * Run agent workflow for the given taskContext and user input.
     * Simply delegates to KoogWorkflowService, which runs the Koog agent.
     */
    suspend fun run(
        task: PendingTaskDocument,
        userInput: String,
    ): ChatResponse {
        logger.info { "AGENT_ORCHESTRATOR_START: correlationId=${task.correlationId}" }

        val response = koogWorkflowService.run(task, userInput)

        logger.info { "AGENT_ORCHESTRATOR_COMPLETE: correlationId=${task.correlationId}" }
        return response
    }
}
