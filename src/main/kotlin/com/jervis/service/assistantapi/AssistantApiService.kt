package com.jervis.service.assistantapi

import com.jervis.dto.ModelData
import com.jervis.dto.Usage
import com.jervis.dto.completion.ChatCompletionRequest
import com.jervis.dto.completion.CompletionChoice
import com.jervis.dto.completion.CompletionRequest
import com.jervis.dto.completion.CompletionResponse
import com.jervis.dto.embedding.EmbeddingItem
import com.jervis.dto.embedding.EmbeddingRequest
import com.jervis.dto.embedding.EmbeddingResponse
import com.jervis.service.indexer.EmbeddingService
import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.project.ProjectService
import com.jervis.service.rag.RagOrchestrator
import org.bson.types.ObjectId
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
        projectService.getAllProjectsBlocking().map { project ->
            ModelData(
                id = project.name,
                `object` = "model",
                ownedBy = "jervis",
            )
        }

    /**
     * Process a completion request
     *
     * @param request The completion request
     * @return The completion response
     */
    fun processCompletion(request: CompletionRequest): CompletionResponse {
        resolveProjectId(request.model)

        // For simple completions, we'll just use the LLM coordinator directly
        val llmResponse =
            llmCoordinator.processQueryBlocking(
                query = request.prompt,
                context = "",
                options =
                    mapOf(
                        "temperature" to (request.temperature ?: 0.7f),
                        "max_tokens" to (request.maxTokens ?: 1024),
                    ),
            )

        return CompletionResponse(
            id = "comply-${UUID.randomUUID()}",
            model = request.model ?: "default",
            created = Instant.now().epochSecond,
            choices =
                listOf(
                    CompletionChoice(
                        text = llmResponse.answer,
                        index = 0,
                        logprobs = null,
                        finishReason = llmResponse.finishReason,
                    ),
                ),
            usage =
                Usage(
                    promptTokens = llmResponse.promptTokens,
                    completionTokens = llmResponse.completionTokens,
                    totalTokens = llmResponse.totalTokens,
                ),
        )
    }

    /**
     * Process a chat completion request
     *
     * @param request The chat completion request
     * @return The completion response
     */
    suspend fun processChatCompletion(request: ChatCompletionRequest): CompletionResponse {
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
                        "max_tokens" to (request.maxTokens ?: 1024),
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
                        finishReason = ragResponse.finishReason,
                    ),
                ),
            usage =
                Usage(
                    promptTokens = ragResponse.promptTokens,
                    completionTokens = ragResponse.completionTokens,
                    totalTokens = ragResponse.totalTokens,
                ),
        )
    }

    /**
     * Process an embedding request
     *
     * @param request The embeddings request
     * @return The embedding response
     */
    suspend fun processEmbeddings(request: EmbeddingRequest): EmbeddingResponse {
        val embeddings = embeddingService.generateEmbedding(request.input)

        val items =
            request.input.mapIndexed { index, _ ->
                EmbeddingItem(
                    embedding = embeddings[index],
                    index = index,
                )
            }

        return EmbeddingResponse(
            data = items,
            model = request.model,
            usage =
                Usage(
                    promptTokens = request.input.sumOf { it.length / 4 },
                    completionTokens = 0,
                    totalTokens = request.input.sumOf { it.length / 4 },
                ),
        )
    }

    /**
     * Resolve a project ID from a model name
     *
     * @param modelName The name of the model (which is the project name)
     * @return The project ID
     */
    private fun resolveProjectId(modelName: String?): ObjectId {
        if (modelName.isNullOrBlank()) {
            // Use the default project
            return projectService.getDefaultProjectBlocking()?.id ?: projectService
                .getAllProjectsBlocking()
                .firstOrNull()
                ?.id
                ?: throw IllegalStateException("No projects available")
        }

        // Find the project by name
        val project = projectService.getAllProjectsBlocking().find { it.name == modelName }
        return project?.id ?: throw IllegalArgumentException("Project not found for model: $modelName")
    }
}
