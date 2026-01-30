package com.jervis.service.agent.coordinator

import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.ChatResponseDto
import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.service.background.TaskService
import com.jervis.service.text.CzechKeyboardNormalizer
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
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
    private val taskService: TaskService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Enqueue a chat message for background processing.
     *
     * NOTE: This creates a NEW TaskDocument each time.
     * For chat continuation, the RPC layer should find/reuse existing TaskDocument instead.
     */
    suspend fun enqueueChatTask(
        text: String,
        clientId: ClientId,
        projectId: ProjectId?,
        
        attachments: List<AttachmentMetadata> = emptyList(),
    ): TaskDocument {
        val normalizedText = czechKeyboardNormalizer.convertIfMistyped(text)

        // Create TaskDocument - this is the single source of truth
        return taskService.createTask(
            taskType = TaskTypeEnum.USER_INPUT_PROCESSING,
            content = normalizedText,
            clientId = clientId,
            projectId = projectId,
            correlationId = ObjectId().toString(),
            sourceUrn = SourceUrn.chat(clientId),
            state = TaskStateEnum.READY_FOR_GPU,
            attachments = attachments,
            
        )
    }

    /**
     * Handle incoming chat request by creating a TaskContext and running Koog workflow.
     * This is the main entry point used by controllers.
     *
     * @param onProgress Callback for sending progress updates to client
     */
    suspend fun handle(
        text: String,
        ctx: ChatRequestContextDto,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ): ChatResponseDto {
        val normalizedText = czechKeyboardNormalizer.convertIfMistyped(text)
        if (normalizedText != text) {
            logger.info { "AGENT_INPUT_NORMALIZED: original='${text.take(100)}' normalized='${normalizedText.take(100)}'" }
        }
        
        val clientIdTyped = try { ClientId.fromString(ctx.clientId) } catch (e: Exception) { ClientId(ObjectId(ctx.clientId)) }
        val projectIdTyped = ctx.projectId?.let { 
            try { ProjectId.fromString(it) } catch (e: Exception) { ProjectId(ObjectId(it)) }
        }

        logger.info { "AGENT_HANDLE_START: text='${normalizedText.take(100)}' clientId=${clientIdTyped} projectId=${projectIdTyped}" }

        val taskContext =
            TaskDocument(
                type = TaskTypeEnum.USER_INPUT_PROCESSING,
                content = normalizedText,
                projectId = projectIdTyped,
                clientId = clientIdTyped,
                correlationId = ObjectId().toString(),
                sourceUrn = SourceUrn.chat(clientIdTyped),
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
    ): ChatResponseDto {
        logger.info { "AGENT_ORCHESTRATOR_START: correlationId=${task.correlationId}" }

        val response = koogWorkflowService.run(task, userInput, onProgress)

        logger.info { "AGENT_ORCHESTRATOR_COMPLETE: correlationId=${task.correlationId}" }
        return response
    }
}
