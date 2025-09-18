package com.jervis.service.gateway.core

import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.PromptConfig
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.llm.LlmResponse
import kotlinx.coroutines.reactor.mono
import mu.KotlinLogging
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

/**
 * Service responsible for executing LLM calls with proper reactive patterns and error handling.
 * Handles client selection, call execution, timing, and error mapping.
 */
@Service
class LlmCallExecutor(
    private val clients: List<com.jervis.service.gateway.clients.ProviderClient>,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Executes LLM call for the given candidate model with proper reactive patterns.
     */
    suspend fun executeCall(
        candidate: ModelsProperties.ModelDetail,
        systemPrompt: String,
        userPrompt: String,
        prompt: PromptConfig,
        promptType: PromptTypeEnum,
    ): LlmResponse {
        val provider =
            candidate.provider
                ?: throw IllegalStateException("Provider not specified for candidate")

        val client = findClientForProvider(provider)

        logger.info { "Calling LLM type=$promptType provider=$provider model=${candidate.model}" }
        val startTime = System.nanoTime()

        return try {
            logger.debug { "LLM Request - systemPrompt=$systemPrompt, userPrompt=$userPrompt" }
            val response = client.call(candidate.model, systemPrompt, userPrompt, candidate, prompt)

            validateResponse(response, provider)
            logSuccessfulCall(provider, candidate.model, startTime)
            logger.debug { "LLM Response - $response" }

            response
        } catch (throwable: Throwable) {
            val errorDetail = createErrorDetail(throwable)
            logFailedCall(provider, candidate.model, startTime, errorDetail)
            throw IllegalStateException("LLM call failed for $provider: $errorDetail", throwable)
        }
    }

    /**
     * Creates a reactive Mono for LLM call execution.
     */
    fun executeCallReactive(
        candidate: ModelsProperties.ModelDetail,
        systemPrompt: String,
        userPrompt: String,
        prompt: PromptConfig,
        promptType: PromptTypeEnum,
    ): Mono<LlmResponse> =
        mono {
            executeCall(candidate, systemPrompt, userPrompt, prompt, promptType)
        }

    /**
     * Finds the appropriate client for the given provider.
     */
    private fun findClientForProvider(provider: com.jervis.domain.model.ModelProvider): com.jervis.service.gateway.clients.ProviderClient =
        clients.find { it.provider == provider }
            ?: throw IllegalStateException("No client found for provider $provider")

    /**
     * Validates that the LLM response is not empty.
     */
    private fun validateResponse(
        response: LlmResponse,
        provider: com.jervis.domain.model.ModelProvider,
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
     * Logs successful LLM call with timing information.
     */
    private fun logSuccessfulCall(
        provider: com.jervis.domain.model.ModelProvider,
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
        provider: com.jervis.domain.model.ModelProvider,
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
