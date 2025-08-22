package com.jervis.controller

import com.jervis.domain.model.ModelType
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
import com.jervis.service.agent.coordinator.LanguageOrchestrator
import com.jervis.service.agent.planner.Planner
import com.jervis.service.agent.context.ContextService
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.project.ProjectService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Service for handling completions and chat functionality.
 * Routes calls through Planner for project scoping and uses LanguageOrchestrator for LLM generation.
 */
@Service
class CompletionService(
    private val projectService: ProjectService,
    private val planner: Planner,
    private val languageOrchestrator: LanguageOrchestrator,
    private val embeddingGateway: EmbeddingGateway,
    private val contextService: ContextService,
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

            val initialProject = resolveProject(request.model)
            val context = contextService.persistContext(
                clientName = "unknown",
                projectName = initialProject?.name,
                autoScope = false,
                englishText = null,
                contextId = null,
            )
            val plan = planner.execute(contextId = context.id)
            val projectName = plan.chosenProject.ifBlank { initialProject?.name ?: "Unknown" }

            val answer =
                languageOrchestrator.generate(
                    type = ModelType.CHAT_INTERNAL,
                    userPrompt = request.prompt,
                )

            logger.info { "Completion request processed successfully" }
            return CompletionResponse(
                id = "cmpl-${UUID.randomUUID()}",
                `object` = "text_completion",
                model = projectName,
                created = Instant.now().epochSecond,
                choices =
                    listOf(
                        CompletionChoice(
                            text = answer,
                            index = 0,
                            logprobs = null,
                            finishReason = "stop",
                        ),
                    ),
                usage =
                    Usage(
                        promptTokens = 0,
                        completionTokens = 0,
                        totalTokens = 0,
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

            val initialProject = resolveProject(request.model)
            val context = contextService.persistContext(
                clientName = "unknown",
                projectName = initialProject?.name,
                autoScope = false,
                englishText = null,
                contextId = null,
            )
            val plan = planner.execute(contextId = context.id)
            val projectName = plan.chosenProject.ifBlank { initialProject?.name ?: "Unknown" }

            val answer =
                languageOrchestrator.generate(
                    type = ModelType.CHAT_INTERNAL,
                    userPrompt = userPrompt,
                )

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
                                    content = answer,
                                ),
                            finishReason = "stop",
                        ),
                    ),
                usage =
                    Usage(
                        promptTokens = 0,
                        completionTokens = 0,
                        totalTokens = 0,
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
                val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, text)

                EmbeddingItem(
                    embedding = embedding.toList(),
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
