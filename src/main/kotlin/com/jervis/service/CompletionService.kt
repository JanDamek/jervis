package com.jervis.service

import com.jervis.dto.ChatCompletionRequest
import com.jervis.dto.ChatCompletionResponse
import com.jervis.dto.ChatMessage
import com.jervis.dto.Choice
import com.jervis.dto.CompletionChoice
import com.jervis.dto.CompletionRequest
import com.jervis.dto.CompletionResponse
import com.jervis.dto.EmbeddingItem
import com.jervis.dto.EmbeddingRequest
import com.jervis.dto.EmbeddingResponse
import com.jervis.dto.Usage
import com.jervis.entity.Project
import com.jervis.module.indexer.EmbeddingService
import com.jervis.module.llmcoordinator.LlmCoordinator
import com.jervis.module.ragcore.RagOrchestrator
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Service for handling completions and chat functionality.
 * This service integrates RAG and LLM to provide responses to user queries.
 */
@Service
class CompletionService(
    private val projectService: ProjectService,
    private val ragOrchestrator: RagOrchestrator,
    private val embeddingService: EmbeddingService,
    private val llmCoordinator: LlmCoordinator
) {
    private val logger = KotlinLogging.logger {}
    /**
     * Process a completion request using LLM.
     *
     * @param request The completion request
     * @return The completion response
     */
    fun complete(request: CompletionRequest): CompletionResponse {
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
                            finish_reason = "error",
                        ),
                    ),
                usage = Usage(0, 0, 0),
            )
        }
    }

    /**
     * Process a chat completion request using a four-phase approach:
     * 1. First query to RAG
     * 2. Send result to LLM for query refinement
     * 3. Final RAG query with refined query
     * 4. LLM processing of final RAG result
     *
     * @param request The chat completion request
     * @return The chat completion response
     */
    fun chatComplete(request: ChatCompletionRequest): ChatCompletionResponse {
        try {
            val userPrompt = request.messages.lastOrNull()?.content ?: ""
            logger.info { "Processing chat completion request: ${userPrompt.take(50)}..." }

            val project = resolveProject(request.model)
            val projectName = project?.name ?: "Unknown"
            val projectId = project?.id ?: throw IllegalArgumentException("Project not found")

            // Phase 1: Initial RAG query
            logger.debug { "Phase 1: Initial RAG query" }
            val initialRagResponse = ragOrchestrator.processQuery(userPrompt, projectId)

            // Phase 2: LLM query refinement
            logger.debug { "Phase 2: LLM query refinement" }
            val refinementPrompt = """
                Based on the following user query and initial context, please refine the query to better retrieve relevant information from the knowledge base.

                Original query: $userPrompt

                Initial context:
                ${initialRagResponse.context}

                Please provide a refined query that will help retrieve more relevant information.
            """.trimIndent()

            val llmRefinementResponse = llmCoordinator.processQuery(refinementPrompt, "")
            val refinedQuery = llmRefinementResponse.answer

            logger.debug { "Refined query: $refinedQuery" }

            // Phase 3: Final RAG query with refined query
            logger.debug { "Phase 3: Final RAG query with refined query" }
            val finalRagResponse = ragOrchestrator.processQuery(refinedQuery, projectId)

            // Phase 4: LLM processing of final RAG result
            logger.debug { "Phase 4: LLM processing of final RAG result" }
            val finalPrompt = """
                Please answer the following user query based on the provided context.

                User query: $userPrompt

                Context:
                ${finalRagResponse.context}

                Please provide a comprehensive and accurate answer based only on the information in the context.
            """.trimIndent()

            val finalLlmResponse = llmCoordinator.processQuery(finalPrompt, finalRagResponse.context)

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
                            message = ChatMessage(
                                role = "assistant",
                                content = finalLlmResponse.answer
                            ),
                            finish_reason = finalLlmResponse.finishReason,
                        ),
                    ),
                usage =
                    Usage(
                        prompt_tokens = finalLlmResponse.promptTokens,
                        completion_tokens = finalLlmResponse.completionTokens,
                        total_tokens = finalLlmResponse.totalTokens,
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
                            message = ChatMessage(
                                role = "assistant",
                                content = "Error processing request: ${e.message}"
                            ),
                            finish_reason = "error",
                        ),
                    ),
                usage = Usage(0, 0, 0),
            )
        }
    }

    fun embeddings(request: EmbeddingRequest): EmbeddingResponse {
        // Use the model specified in the request or default to text model
        val model = request.model

        // Generate embeddings for each input text
        val items = request.input.mapIndexed { i, text ->
            // Generate embedding using the embedding service
            val embedding = embeddingService.generateTextEmbedding(text)

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
                    prompt_tokens = promptTokens,
                    completion_tokens = 0,
                    total_tokens = promptTokens,
                ),
        )
    }

    /**
     * Process a chat query using a four-phase approach:
     * 1. First query to RAG
     * 2. Send result to LLM for query refinement
     * 3. Final RAG query with refined query
     * 4. LLM processing of final RAG result
     *
     * @param query The user query
     * @return The response to the query
     */
    fun processQuery(query: String): String {
        try {
            logger.info { "Processing chat query: $query" }

            // Get the active project
            val activeProject = projectService.getActiveProject()
            if (activeProject == null) {
                logger.warn { "No active project found" }
                return "No active project selected. Please select a project first."
            }

            // Phase 1: Initial RAG query
            logger.debug { "Phase 1: Initial RAG query" }
            val initialRagResponse = ragOrchestrator.processQuery(query, activeProject.id!!)

            // Phase 2: LLM query refinement
            logger.debug { "Phase 2: LLM query refinement" }
            val refinementPrompt = """
                Based on the following user query and initial context, please refine the query to better retrieve relevant information from the knowledge base.

                Original query: $query

                Initial context:
                ${initialRagResponse.context}

                Please provide a refined query that will help retrieve more relevant information.
            """.trimIndent()

            val llmRefinementResponse = llmCoordinator.processQuery(refinementPrompt, "")
            val refinedQuery = llmRefinementResponse.answer

            logger.debug { "Refined query: $refinedQuery" }

            // Phase 3: Final RAG query with refined query
            logger.debug { "Phase 3: Final RAG query with refined query" }
            val finalRagResponse = ragOrchestrator.processQuery(refinedQuery, activeProject.id!!)

            // Phase 4: LLM processing of final RAG result
            logger.debug { "Phase 4: LLM processing of final RAG result" }
            val finalPrompt = """
                Please answer the following user query based on the provided context.

                User query: $query

                Context:
                ${finalRagResponse.context}

                Please provide a comprehensive and accurate answer based only on the information in the context.
            """.trimIndent()

            val finalLlmResponse = llmCoordinator.processQuery(finalPrompt, finalRagResponse.context)

            logger.info { "Chat query processed successfully" }
            return finalLlmResponse.answer
        } catch (e: Exception) {
            logger.error(e) { "Error processing chat query: ${e.message}" }
            return "Sorry, an error occurred while processing your query: ${e.message}"
        }
    }

    private fun resolveProject(modelId: String?): Project? =
        if (!modelId.isNullOrBlank()) {
            projectService.getAllProjects().find { it.name == modelId }
        } else {
            projectService.getDefaultProject()
        }
}
