package com.jervis.service.gateway.core

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.gateway.processing.JsonParser
import com.jervis.service.gateway.processing.PromptBuilderService
import com.jervis.service.gateway.processing.TokenEstimationService
import com.jervis.service.gateway.processing.domain.ParsedResponse
import com.jervis.service.gateway.selection.ModelCandidateSelector
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
     * Returns both think content and parsed JSON result.
     */
    suspend fun <T : Any> callLlm(
        type: PromptTypeEnum,
        responseSchema: T,
        quick: Boolean = false,
        mappingValue: Map<String, String> = emptyMap(),
        outputLanguage: String? = null,
        backgroundMode: Boolean,
    ): ParsedResponse<T> {
        require((responseSchema is String).not()) { "Response schema must be a object not string." }
        val prompt = promptRepository.getPrompt(type)

        val mappingValues =
            buildMap {
                putAll(mappingValue)

                // Add J.E.R.V.I.S. identity and core principles
                put("assistantIdentity", "J.E.R.V.I.S.")
                put(
                    "assistantRole",
                    """
                    You are J.E.R.V.I.S. - Just Enough Resources Very Intelligent System.

                    CORE PRINCIPLES:
                    1. UNDERSTAND before ACT - Never execute without understanding context
                    2. CONTEXT is KING - Every action must respect existing patterns and architecture
                    3. GUIDE, don't FORCE - Suggest options with trade-offs, explain reasoning
                    4. PRESERVE CONTINUITY - Maintain architectural consistency
                    5. MINIMAL INTERVENTION - Do the least necessary, explain why

                    You are NOT just a code generator. You are a SYSTEM ADVISOR who understands the complete picture.
                    """.trimIndent(),
                )

                // Inject essential temporal context for all LLM calls
                val now = LocalDateTime.now()
                put("currentDate", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                put("tomorrowDate", now.plusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                put(
                    "currentDayOfWeek",
                    now.dayOfWeek.name
                        .lowercase()
                        .replaceFirstChar { it.uppercase() },
                )
                put(
                    "currentMonth",
                    now.month.name
                        .lowercase()
                        .replaceFirstChar { it.uppercase() },
                )
                put("currentYear", now.year.toString())
                put("nextWeekDate", now.plusWeeks(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
            }

        val systemPrompt = promptBuilderService.buildSystemPrompt(prompt, mappingValues)
        val finalUserPrompt =
            promptBuilderService.buildUserPrompt(
                prompt,
                mappingValues,
                outputLanguage,
                responseSchema,
            )
        val estimatedTokens = tokenEstimationService.estimateTotalTokensNeeded(systemPrompt, finalUserPrompt)

        logger.debug { "Estimated tokens needed: $estimatedTokens for prompt type: $type" }

        val candidates =
            modelCandidateSelector
                .selectCandidates(prompt.modelParams.modelType, quick, estimatedTokens)
                .toList()

        check(candidates.isNotEmpty()) { "No LLM candidates configured for $type" }

        // Check if we need selective processing due to token limits
        val maxTokenLimit = candidates.mapNotNull { it.contextLength }.maxOrNull() ?: 16000

        if (estimatedTokens > maxTokenLimit) {
            logger.info { "Token limit exceeded ($estimatedTokens > $maxTokenLimit), using SelectiveLlmProcessor for type: $type" }

            // Create executor function that uses the normal LLM call logic without recursion
            val executor: suspend (String, String) -> T = executor@{ sysPrompt, usrPrompt ->
                // Try candidates sequentially for each chunk
                var lastException: Exception? = null
                for (candidate in candidates) {
                    try {
                        val response =
                            llmCallExecutor.executeCall(
                                candidate,
                                sysPrompt,
                                usrPrompt,
                                prompt,
                                type,
                                estimatedTokens,
                                backgroundMode,
                            )

                        val provider =
                            requireNotNull(candidate.provider) {
                                "Provider is required for model candidate ${candidate.model}"
                            }
                        val parsedResponse =
                            jsonParser.validateAndParseWithThink(
                                response.answer,
                                responseSchema,
                                provider,
                                candidate.model,
                            )
                        return@executor parsedResponse.result
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        logger.info { "Chunk processing cancelled (background task interrupted)" }
                        throw e
                    } catch (e: Exception) {
                        logger.error { "LLM call failed for provider=${candidate.provider} model=${candidate.model}: ${e.message}" }
                        logger.error { "Full error details: ${e.stackTraceToString()}" }
                        lastException = e
                    }
                }
                throw IllegalStateException("All candidates failed for chunk processing", lastException)
            }

            val selectiveResult =
                selectiveLlmProcessor.processWithTokenLimitHandling(
                    type = type,
                    systemPrompt = systemPrompt,
                    userPrompt = finalUserPrompt,
                    maxTokensPerChunk = maxTokenLimit,
                    executor = executor,
                )

            if (selectiveResult.success && selectiveResult.combinedResult != null) {
                logger.info {
                    "SelectiveLlmProcessor completed successfully: processed=${selectiveResult.processedChunks}, failed=${selectiveResult.failedChunks}"
                }
                // For SelectiveLlmProcessor, we don't have think content as it processes chunks
                return ParsedResponse(null, selectiveResult.combinedResult)
            } else {
                error {
                    "SelectiveLlmProcessor failed: processed=${selectiveResult.processedChunks}, failed=${selectiveResult.failedChunks}"
                }
            }
        }

        // Normal processing for prompts within token limits
        // Try candidates sequentially - only proceed to next on failure
        for (candidate in candidates) {
            try {
                val response =
                    llmCallExecutor.executeCall(
                        candidate,
                        systemPrompt,
                        finalUserPrompt,
                        prompt,
                        type,
                        estimatedTokens,
                        backgroundMode,
                    )

                // JSON PARSING WITH THINK EXTRACTION
                val provider =
                    requireNotNull(candidate.provider) {
                        "Provider is required for model candidate ${candidate.model}"
                    }
                return jsonParser.validateAndParseWithThink(
                    response.answer,
                    responseSchema,
                    provider,
                    candidate.model,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                logger.info { "LLM call cancelled (background task interrupted)" }
                throw e
            } catch (e: Exception) {
                logger.error { "LLM call failed for provider=${candidate.provider} model=${candidate.model}: ${e.message}" }
                logger.error { "Full error details: ${e.stackTraceToString()}" }

                // Continue to next candidate only on provider failure or insufficient tokens
                if (candidates.indexOf(candidate) == candidates.size - 1) {
                    // This was the last candidate, preserve original error in exception message
                    throw IllegalStateException("All LLM candidates failed for $type. Last error: ${e.message}", e)
                }
                // Otherwise continue to next candidate
            }
        }

        throw IllegalStateException("No successful LLM candidates for $type")
    }
}
