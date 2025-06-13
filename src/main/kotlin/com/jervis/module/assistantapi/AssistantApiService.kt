package com.jervis.module.assistantapi

import com.jervis.dto.ChatCompletionRequest
import com.jervis.dto.CompletionChoice
import com.jervis.dto.CompletionRequest
import com.jervis.dto.CompletionResponse
import com.jervis.dto.Usage
import com.jervis.dto.EmbeddingItem
import com.jervis.dto.EmbeddingRequest
import com.jervis.dto.EmbeddingResponse
import com.jervis.dto.ModelData
import com.jervis.module.indexer.EmbeddingService
import com.jervis.module.llmcoordinator.LlmCoordinator
import com.jervis.module.ragcore.RagOrchestrator
import com.jervis.service.ProjectService
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Service for handling Assistant API requests.
 * This service processes requests from the Assistant API controller and coordinates with other services.
 */
@Service
class AssistantApiService(
    private val projectService: ProjectService,
    private val ragOrchestrator: RagOrchestrator,
    private val llmCoordinator: LlmCoordinator,
    private val embeddingService: EmbeddingService,
) {
    /**
     * Get available models
     *
     * @return List of available models
     */
    fun getAvailableModels(): List<ModelData> =
        projectService.getAllProjects().map { project ->
            ModelData(
                id = project.name ?: project.id.toString(),
                `object` = "model",
                owned_by = "jervis",
            )
        }

    /**
     * Process a completion request
     *
     * @param request The completion request
     * @return The completion response
     */
    fun processCompletion(request: CompletionRequest): CompletionResponse {
        val projectId = resolveProjectId(request.model)

        // For simple completions, we'll just use the LLM coordinator directly
        val llmResponse =
            llmCoordinator.processQuery(
                query = request.prompt,
                context = "",
                options =
                    mapOf(
                        "temperature" to (request.temperature ?: 0.7f),
                        "max_tokens" to (request.max_tokens ?: 1024),
                    ),
            )

        return CompletionResponse(
            id = "cmpl-${UUID.randomUUID()}",
            model = request.model ?: "default",
            created = Instant.now().epochSecond,
            choices =
                listOf(
                    CompletionChoice(
                        text = llmResponse.answer,
                        index = 0,
                        logprobs = null,
                        finish_reason = llmResponse.finishReason,
                    ),
                ),
            usage =
                Usage(
                    prompt_tokens = llmResponse.promptTokens,
                    completion_tokens = llmResponse.completionTokens,
                    total_tokens = llmResponse.totalTokens,
                ),
        )
    }

    /**
     * Process a chat completion request
     *
     * @param request The chat completion request
     * @return The completion response
     */
    fun processChatCompletion(request: ChatCompletionRequest): CompletionResponse {
        val projectId = resolveProjectId(request.model)

        // Extract the user's query from the last message
        val userQuery = request.messages.lastOrNull { it.role == "user" }?.content ?: ""

        // Use the RAG orchestrator to process the query
        val ragResponse =
            ragOrchestrator.processQuery(
                query = userQuery,
                projectId = projectId,
                options =
                    mapOf(
                        "temperature" to (request.temperature ?: 0.7f),
                        "max_tokens" to (request.max_tokens ?: 1024),
                    ),
            )

        return CompletionResponse(
            id = "chat-${UUID.randomUUID()}",
            model = request.model ?: "default",
            created = Instant.now().epochSecond,
            choices =
                listOf(
                    CompletionChoice(
                        text = ragResponse.answer,
                        index = 0,
                        logprobs = null,
                        finish_reason = ragResponse.finishReason,
                    ),
                ),
            usage =
                Usage(
                    prompt_tokens = ragResponse.promptTokens,
                    completion_tokens = ragResponse.completionTokens,
                    total_tokens = ragResponse.totalTokens,
                ),
        )
    }

    /**
     * Process an embeddings request
     *
     * @param request The embeddings request
     * @return The embeddings response
     */
    fun processEmbeddings(request: EmbeddingRequest): EmbeddingResponse {
        val embeddings = embeddingService.generateTextEmbeddings(request.input)

        val items =
            request.input.mapIndexed { index, text ->
                EmbeddingItem(
                    embedding = embeddings[index],
                    index = index,
                )
            }

        return EmbeddingResponse(
            data = items,
            model = request.model ?: "default-embedding-model",
            usage =
                Usage(
                    prompt_tokens = request.input.sumOf { it.length / 4 },
                    completion_tokens = 0,
                    total_tokens = request.input.sumOf { it.length / 4 },
                ),
        )
    }

    /**
     * Resolve a project ID from a model name
     *
     * @param modelName The name of the model (which is the project name)
     * @return The project ID
     */
    private fun resolveProjectId(modelName: String?): Long {
        if (modelName.isNullOrBlank()) {
            // Use the default project
            return projectService.getDefaultProject()?.id ?: projectService.getAllProjects().firstOrNull()?.id
                ?: throw IllegalStateException("No projects available")
        }

        // Find the project by name
        val project = projectService.getAllProjects().find { it.name == modelName }
        return project?.id ?: throw IllegalArgumentException("Project not found for model: $modelName")
    }
}
