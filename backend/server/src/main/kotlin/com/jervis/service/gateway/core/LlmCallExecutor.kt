package com.jervis.service.gateway.core

import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.configuration.properties.ModelsProperties
import com.jervis.domain.llm.LlmResponse
import com.jervis.service.debug.DebugService
import com.jervis.service.gateway.clients.ProviderClient
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Service responsible for executing LLM calls with proper reactive patterns and error handling.
 * Handles client selection, call execution, timing, and error mapping.
 */
@Service
class LlmCallExecutor(
    private val clients: List<ProviderClient>,
    private val debugService: DebugService,
    private val llmLoadMonitor: com.jervis.service.background.LlmLoadMonitor,
    private val providerConcurrencyManager: ProviderConcurrencyManager,
    private val modelConcurrencyManager: ModelConcurrencyManager,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Executes LLM call for the given candidate model with proper reactive patterns.
     * Routes to streaming if a debug window is active.
     * Respects per-provider concurrency limits - will suspend if the provider is at capacity.
     */
    suspend fun executeCall(
        candidate: ModelsProperties.ModelDetail,
        systemPrompt: String,
        userPrompt: String,
        prompt: PromptConfig,
        promptType: PromptTypeEnum,
        estimatedTokens: Int,
        correlationId: String,
        backgroundMode: Boolean = false,
    ): LlmResponse {
        val provider =
            candidate.provider
                ?: throw IllegalStateException("Provider not specified for candidate")
        val modelName = candidate.model
        val modelLimit = candidate.concurrency

        return modelConcurrencyManager.withConcurrencyControl(provider, modelName, modelLimit) {
            providerConcurrencyManager.withConcurrencyControl(provider) {
                val client = findClientForProvider(provider)

                logger.info { "Calling LLM type=$promptType provider=$provider model=${candidate.model} background=$backgroundMode" }
                val startTime = System.nanoTime()

            if (!backgroundMode) {
                llmLoadMonitor.registerRequestStart()
            }

            try {
                val debugSessionId = UUID.randomUUID().toString()

                debugService.sessionStarted(
                    sessionId = debugSessionId,
                    promptType = promptType.name,
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    correlationId = correlationId,
                )

                val response =
                    executeStreamingCall(
                        client,
                        candidate,
                        systemPrompt,
                        userPrompt,
                        prompt,
                        estimatedTokens,
                        debugSessionId,
                    )

                validateResponse(response, provider)
                logSuccessfulCall(provider, candidate.model, startTime)

                response
            } catch (e: kotlinx.coroutines.CancellationException) {
                logger.info { "LLM call cancelled for $provider (background task interrupted)" }
                throw e
            } catch (throwable: Throwable) {
                val errorDetail = createErrorDetail(throwable)
                logFailedCall(provider, candidate.model, startTime, errorDetail)
                throw IllegalStateException("LLM call failed for $provider: $errorDetail", throwable)
            } finally {
                // Always unregister requests (foreground or background)
                if (!backgroundMode) {
                    llmLoadMonitor.registerRequestEnd()
                }
            }
            }
        }
    }

    /**
     * Executes streaming LLM call and converts to regular LlmResponse.
     * Maintains fail-first approach - no fallback on streaming failure.
     */
    private suspend fun executeStreamingCall(
        client: ProviderClient,
        candidate: ModelsProperties.ModelDetail,
        systemPrompt: String,
        userPrompt: String,
        prompt: PromptConfig,
        estimatedTokens: Int,
        debugSessionId: String,
    ): LlmResponse {
        val responseBuilder = StringBuilder()
        var model = candidate.model
        var promptTokens = 0
        var completionTokens = 0
        var totalTokens = 0
        var finishReason = "stop"

        // Collect streaming response
        client
            .callWithStreaming(
                candidate.model,
                systemPrompt,
                userPrompt,
                candidate,
                prompt,
                estimatedTokens,
                debugSessionId,
            ).collect { chunk ->
                if (chunk.content.isNotEmpty()) {
                    responseBuilder.append(chunk.content)
                    // Send debug chunk directly to WebSocket
                    debugService.responseChunk(
                        sessionId = debugSessionId,
                        chunk = chunk.content,
                    )
                }

                if (chunk.isComplete && chunk.metadata.isNotEmpty()) {
                    model = chunk.metadata["model"] as? String ?: candidate.model
                    promptTokens = chunk.metadata["prompt_tokens"] as? Int ?: 0
                    completionTokens = chunk.metadata["completion_tokens"] as? Int ?: 0
                    totalTokens = chunk.metadata["total_tokens"] as? Int ?: 0
                    finishReason = chunk.metadata["finish_reason"] as? String ?: "stop"
                }
            }

        // Send the debug session completed directly to WebSocket
        debugService.sessionCompleted(debugSessionId)

        val finalResponse =
            LlmResponse(
                answer = responseBuilder.toString(),
                model = model,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                finishReason = finishReason,
            )

        return finalResponse
    }

    /**
     * Finds the appropriate client for the given provider.
     */
    private fun findClientForProvider(provider: com.jervis.domain.model.ModelProviderEnum): ProviderClient =
        clients.find { it.provider == provider }
            ?: throw IllegalStateException("No client found for provider $provider")

    /**
     * Validates that the LLM response is not empty.
     */
    private fun validateResponse(
        response: LlmResponse,
        provider: com.jervis.domain.model.ModelProviderEnum,
    ) {
        require(response.answer.isNotBlank()) { "Empty response from $provider" }
    }

    /**
     * Creates detailed error information from throwable for logging.
     */
    private fun createErrorDetail(throwable: Throwable): String =
        when (throwable) {
            is org.springframework.web.reactive.function.client.WebClientResponseException ->
                "status=${throwable.statusCode.value()} body='${throwable.responseBodyAsString.take(500)}'"

            else -> "${throwable::class.simpleName}: ${throwable.message}"
        }

    /**
     * Logs a successful LLM call with timing information.
     */
    private fun logSuccessfulCall(
        provider: com.jervis.domain.model.ModelProviderEnum,
        model: String,
        startTime: Long,
    ) {
        val duration = calculateDurationMs(startTime)
        logger.info { "LLM call succeeded provider=$provider model=$model in ${duration}ms" }
    }

    /**
     * Logs failed LLM call with timing and error information.
     */
    private fun logFailedCall(
        provider: com.jervis.domain.model.ModelProviderEnum,
        model: String,
        startTime: Long,
        errorDetail: String,
    ) {
        val duration = calculateDurationMs(startTime)
        logger.error { "LLM call failed provider=$provider model=$model after ${duration}ms: $errorDetail" }
    }

    /**
     * Calculates duration in milliseconds from start time.
     */
    private fun calculateDurationMs(startTime: Long): Long = (System.nanoTime() - startTime) / 1_000_000
}
