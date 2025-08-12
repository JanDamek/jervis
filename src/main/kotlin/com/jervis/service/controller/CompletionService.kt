package com.jervis.service.controller

import com.jervis.dto.ChatMessage
import com.jervis.dto.Choice
import com.jervis.dto.Usage
import com.jervis.dto.completion.ChatCompletionRequest
import com.jervis.dto.completion.ChatCompletionResponse
import com.jervis.dto.completion.CompletionChoice
import com.jervis.dto.completion.CompletionRequest
import com.jervis.dto.completion.CompletionResponse
import com.jervis.dto.embedding.EmbeddingItem
import com.jervis.dto.embedding.EmbeddingRequest
import com.jervis.dto.embedding.EmbeddingResponse
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.indexer.EmbeddingService
import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.project.ProjectService
import com.jervis.service.rag.RagQueryService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Service for handling completions and chat functionality.
 * This service integrates with LLM and RAG to provide responses to user queries.
 */
@Service
class CompletionService(
    private val projectService: ProjectService,
    private val embeddingService: EmbeddingService,
    private val llmCoordinator: LlmCoordinator,
    private val ragQueryService: RagQueryService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Process a completion request using LLM.
     *
     * @param request The completion request
     * @return The completion response
     */
    suspend fun complete(request: CompletionRequest): CompletionResponse {
        try {
            logger.info { "Processing completion request: ${request.prompt.take(50)}..." }

            val project = resolveProject(request.model)
            val projectName = project?.name ?: "Unknown"

            // Process the prompt with LLM
            val llmResponse = llmCoordinator.processQuery(request.prompt, "")

            logger.info { "Completion request processed successfully" }
            return CompletionResponse(
                id = "cmpl-${UUID.randomUUID()}",
                `object` = "text_completion",
                model = projectName,
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
        } catch (e: Exception) {
            logger.error(e) { "Error processing completion request: ${e.message}" }
            return CompletionResponse(
                id = "error-${UUID.randomUUID()}",
                `object` = "text_completion",
                model = "error",
                created = Instant.now().epochSecond,
                choices =
                    listOf(
                        CompletionChoice(
                            text = "Error processing request: ${e.message}",
                            index = 0,
                            logprobs = null,
                            finishReason = "error",
                        ),
                    ),
                usage = Usage(promptTokens = 0, completionTokens = 0, totalTokens = 0),
            )
        }
    }

    /**
     * Process a chat completion request using RAG.
     *
     * @param request The chat completion request
     * @return The chat completion response
     */
    suspend fun chatComplete(request: ChatCompletionRequest): ChatCompletionResponse {
        try {
            val userPrompt = request.messages.lastOrNull()?.content ?: ""
            logger.info { "Processing chat completion request: ${userPrompt.take(50)}..." }

            val project = resolveProject(request.model)
            val projectName = project?.name ?: "Unknown"
            val projectId = project?.id ?: throw IllegalArgumentException("Project not found")

            // Process the query using the RAG query service
            val result = ragQueryService.processRagQuery(userPrompt, projectId)

            logger.info { "Chat completion request processed successfully" }
            return ChatCompletionResponse(
                id = "chat-${UUID.randomUUID()}",
                `object` = "chat.completion",
                model = projectName,
                created = Instant.now().epochSecond,
                choices =
                    listOf(
                        Choice(
                            index = 0,
                            message =
                                ChatMessage(
                                    role = "assistant",
                                    content = result.answer,
                                ),
                            finishReason = result.finishReason,
                        ),
                    ),
                usage =
                    Usage(
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        totalTokens = result.totalTokens,
                    ),
            )
        } catch (e: Exception) {
            logger.error(e) { "Error processing chat completion request: ${e.message}" }
            return ChatCompletionResponse(
                id = "error-${UUID.randomUUID()}",
                `object` = "chat.completion",
                model = "error",
                created = Instant.now().epochSecond,
                choices =
                    listOf(
                        Choice(
                            index = 0,
                            message =
                                ChatMessage(
                                    role = "assistant",
                                    content = "Error processing request: ${e.message}",
                                ),
                            finishReason = "error",
                        ),
                    ),
                usage = Usage(promptTokens = 0, completionTokens = 0, totalTokens = 0),
            )
        }
    }

    /**
     * Generate embeddings for text inputs.
     *
     * @param request The embedding request
     * @return The embedding response
     */
    suspend fun embeddings(request: EmbeddingRequest): EmbeddingResponse {
        // Use the model specified in the request or default to text model
        val model = request.model

        // Generate embeddings for each input text
        val items =
            request.input.mapIndexed { i, text ->
                // Generate embedding using the embedding service
                val embedding = embeddingService.generateEmbedding(text)

                EmbeddingItem(
                    embedding = embedding,
                    index = i,
                )
            }

        // Calculate token usage (approximate)
        val promptTokens = request.input.sumOf { it.length / 4 }

        return EmbeddingResponse(
            data = items,
            model = model ?: "default-model",
            usage =
                Usage(
                    promptTokens = promptTokens,
                    completionTokens = 0,
                    totalTokens = promptTokens,
                ),
        )
    }

    /**
     * Process a chat query using RAG.
     *
     * @param query The user query
     * @return The response to the query
     */
    suspend fun processQuery(query: String): String {
        try {
            logger.info { "Processing chat query: $query" }

            val result = ragQueryService.processRagQuery(query)

            logger.info { "Chat query processed successfully" }
            return result.answer
        } catch (e: Exception) {
            logger.error(e) { "Error processing chat query: ${e.message}" }
            return "Sorry, an error occurred while processing your query: ${e.message}"
        }
    }

    /**
     * Resolves a project based on the model ID.
     *
     * @param modelId The model ID, which corresponds to a project name
     * @return The resolved project, or null if not found
     */
    private suspend fun resolveProject(modelId: String?): ProjectDocument? =
        if (!modelId.isNullOrBlank()) {
            projectService.getAllProjects().find { it.name == modelId }
        } else {
            projectService.getDefaultProject()
        }
}
