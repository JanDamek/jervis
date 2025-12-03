package com.jervis.service.agent.coordinator

import com.jervis.domain.plan.Plan
import com.jervis.dto.ChatResponse
import com.jervis.repository.ClientMongoRepository
import com.jervis.repository.ProjectMongoRepository
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * AgentOrchestratorService - simplified orchestrator that runs KoogWorkflowService.
 *
 * This replaces the old complex planner/executor architecture with direct Koog agent execution.
 * No more MCP tools, no more multi-phase planning - just straight Koog workflow.
 */
@Service
class AgentOrchestratorService(
    private val koogWorkflowService: KoogWorkflowService,
    private val clientRepository: ClientMongoRepository,
    private val projectRepository: ProjectMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Handle incoming chat request by creating a Plan and running Koog workflow.
     * This is the main entry point used by controllers.
     */
    suspend fun handle(
        text: String,
        ctx: com.jervis.dto.ChatRequestContext,
        background: Boolean = false,
    ): ChatResponse {
        logger.info { "AGENT_HANDLE_START: text='${text.take(100)}' clientId=${ctx.clientId} projectId=${ctx.projectId}" }

        // Create Plan
        val clientDoc = clientRepository.findById(ObjectId(ctx.clientId))
            ?: throw IllegalArgumentException("Client not found: ${ctx.clientId}")

        val projectDoc = ctx.projectId?.let { projectRepository.findById(ObjectId(it)) }

        val plan = Plan(
            id = ObjectId(),
            taskInstruction = text,
            originalLanguage = "EN", // Default to English for now
            englishInstruction = text,
            initialRagQueries = listOf(text),
            clientDocument = clientDoc,
            projectDocument = projectDoc,
            backgroundMode = background,
            correlationId = ObjectId().toHexString(), // Generate new correlation ID
        )

        // Run Koog workflow
        val response = run(plan, text)

        logger.info { "AGENT_HANDLE_COMPLETE: correlationId=${plan.correlationId}" }
        return response
    }

    /**
     * Run agent workflow for the given plan and user input.
     * Simply delegates to KoogWorkflowService which runs the Koog agent.
     */
    suspend fun run(plan: Plan, userInput: String): ChatResponse {
        logger.info { "AGENT_ORCHESTRATOR_START: planId=${plan.id} correlationId=${plan.correlationId}" }

        val response = koogWorkflowService.run(plan, userInput)

        logger.info { "AGENT_ORCHESTRATOR_COMPLETE: planId=${plan.id} correlationId=${plan.correlationId}" }
        return response
    }

    /**
     * Handle background task execution.
     * Used by BackgroundEngine to process pending tasks.
     */
    suspend fun handleBackgroundTask(
        text: String,
        clientId: String,
        projectId: String?,
    ): ChatResponse {
        val clientDoc = clientRepository.findById(ObjectId(clientId))
            ?: throw IllegalArgumentException("Client not found: $clientId")

        val projectDoc = projectId?.let { projectRepository.findById(ObjectId(it)) }

        val plan = Plan(
            id = ObjectId(),
            taskInstruction = text,
            originalLanguage = "EN",
            englishInstruction = text,
            initialRagQueries = listOf(text),
            clientDocument = clientDoc,
            projectDocument = projectDoc,
            backgroundMode = true,
            correlationId = ObjectId().toHexString(),
        )

        return run(plan, text)
    }

    /**
     * Compatibility method for BackgroundEngine that needs last plan context.
     * Since we don't maintain plan history anymore, we return an empty string.
     */
    fun getLastPlanContext(clientId: String, projectId: String?): String = ""
}
