package com.jervis.service.gateway.core

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.gateway.processing.JsonParser
import com.jervis.service.gateway.processing.PromptBuilderService
import com.jervis.service.gateway.processing.TokenEstimationService
import com.jervis.service.gateway.selection.ModelCandidateSelector
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.flow.toList
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
    private val selectiveLlmProcessor: SelectiveLlmProcessor,
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
        stepContext: String = "",
    ): T {
        require((responseSchema is String).not()) { "Response schema must be a object not string." }
        val prompt = promptRepository.getPromptLegacy(type)

        val enhancedMappingValues =
            buildMap {
                putAll(mappingValue)
                put("stepContext", stepContext) // Always include stepContext, even if empty
            }

        val systemPrompt = promptBuilderService.buildSystemPrompt(prompt, enhancedMappingValues)
        val finalUserPrompt =
            promptBuilderService.buildUserPrompt(
                prompt,
                enhancedMappingValues,
                userPrompt,
                outputLanguage,
                responseSchema,
            )
        val estimatedTokens = tokenEstimationService.estimateTotalTokensNeeded(systemPrompt, finalUserPrompt)

        logger.debug { "Estimated tokens needed: $estimatedTokens for prompt type: $type" }

        val candidates =
            modelCandidateSelector
                .selectCandidates(prompt.modelParams.modelType, quick, estimatedTokens)
                .toList()

        if (candidates.isEmpty()) {
            throw IllegalStateException("No LLM candidates configured for $type")
        }

        // Check if we need selective processing due to token limits
        val maxTokenLimit = candidates.mapNotNull { it.maxTokens }.maxOrNull() ?: 16000
        
        if (estimatedTokens > maxTokenLimit) {
            logger.info { "Token limit exceeded ($estimatedTokens > $maxTokenLimit), using SelectiveLlmProcessor for type: $type" }
            
            // Create executor function that uses the normal LLM call logic without recursion
            val executor: suspend (String, String) -> T = executor@{ sysPrompt, usrPrompt ->
                // Try candidates sequentially for each chunk
                var lastException: Exception? = null
                for (candidate in candidates) {
                    try {
                        val response = llmCallExecutor.executeCall(candidate, sysPrompt, usrPrompt, prompt, type)
                        
                        return@executor jsonParser.validateAndParse(
                            response.answer,
                            responseSchema,
                            candidate.provider!!,
                            candidate.model,
                        )
                    } catch (e: Exception) {
                        logger.warn { "LLM call failed for provider=${candidate.provider} model=${candidate.model}: ${e.message}" }
                        lastException = e
                    }
                }
                throw IllegalStateException("All candidates failed for chunk processing", lastException)
            }
            
            val selectiveResult = selectiveLlmProcessor.processWithTokenLimitHandling(
                type = type,
                systemPrompt = systemPrompt,
                userPrompt = finalUserPrompt,
                responseSchema = responseSchema,
                quick = quick,
                outputLanguage = outputLanguage,
                stepContext = stepContext,
                maxTokensPerChunk = maxTokenLimit,
                executor = executor
            )
            
            if (selectiveResult.success && selectiveResult.combinedResult != null) {
                logger.info { "SelectiveLlmProcessor completed successfully: processed=${selectiveResult.processedChunks}, failed=${selectiveResult.failedChunks}" }
                return selectiveResult.combinedResult
            } else {
                throw IllegalStateException("SelectiveLlmProcessor failed: processed=${selectiveResult.processedChunks}, failed=${selectiveResult.failedChunks}")
            }
        }

        // Normal processing for prompts within token limits
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
