package com.jervis.service.gateway.core

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.gateway.processing.JsonParser
import com.jervis.service.gateway.processing.PromptBuilderService
import com.jervis.service.gateway.processing.TokenEstimationService
import com.jervis.service.gateway.selection.ModelCandidateSelector
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.reactive.awaitFirst
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Main gateway service for LLM interactions with strict JSON validation.
 * Coordinates specialized services using reactive patterns and coroutines.
 * NO FALLBACK MECHANISMS - either succeeds or fails with clear errors.
 */
@Service
class LlmGateway(
    private val promptRepository: PromptRepository,
    private val tokenEstimationService: TokenEstimationService,
    private val modelCandidateSelector: ModelCandidateSelector,
    private val promptBuilderService: PromptBuilderService,
    private val jsonParser: JsonParser,
    private val llmCallExecutor: LlmCallExecutor,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Main entry point for LLM calls with strict JSON validation.
     * Uses enhanced prompt building and eliminates all fallback mechanisms.
     */
    suspend fun <T : Any> callLlm(
        type: PromptTypeEnum,
        userPrompt: String,
        quick: Boolean,
        responseSchema: T,
        mappingValue: Map<String, String> = emptyMap(),
        outputLanguage: String = "EN",
        stepContext: String = "", // Context from previous steps
    ): T {
        val prompt = promptRepository.getPrompt(type)

        // Include stepContext in mapping values for placeholder replacement
        val enhancedMappingValues =
            buildMap {
                putAll(mappingValue)
                put("stepContext", stepContext) // Always include stepContext, even if empty
            }

        val systemPrompt = promptBuilderService.buildSystemPrompt(prompt, enhancedMappingValues, responseSchema)
        val finalUserPrompt =
            promptBuilderService.buildUserPrompt(prompt, enhancedMappingValues, userPrompt, outputLanguage)
        val estimatedTokens = tokenEstimationService.estimateTotalTokensNeeded(systemPrompt, finalUserPrompt)

        logger.debug { "Estimated tokens needed: $estimatedTokens for prompt type: $type" }

        val candidates =
            modelCandidateSelector
                .selectCandidates(prompt.modelParams.modelType, quick, estimatedTokens)
                .collectList()
                .awaitFirst()

        if (candidates.isEmpty()) {
            throw IllegalStateException("No LLM candidates configured for $type")
        }

        // Try candidates sequentially - only proceed to next on failure
        for (candidate in candidates) {
            try {
                val response = llmCallExecutor.executeCall(candidate, systemPrompt, finalUserPrompt, prompt, type)

                // SIMPLE JSON PARSING - NO VALIDATION
                return jsonParser.validateAndParse(
                    response.answer,
                    responseSchema,
                    candidate.provider!!,
                    candidate.model,
                )
            } catch (e: Exception) {
                logger.warn { "LLM call failed for provider=${candidate.provider} model=${candidate.model}: ${e.message}" }

                // Continue to next candidate only on provider failure or insufficient tokens
                if (candidates.indexOf(candidate) == candidates.size - 1) {
                    // This was the last candidate, re-throw the exception
                    throw IllegalStateException("All LLM candidates failed for $type", e)
                }
                // Otherwise continue to next candidate
            }
        }

        throw IllegalStateException("No successful LLM candidates for $type")
    }
}
