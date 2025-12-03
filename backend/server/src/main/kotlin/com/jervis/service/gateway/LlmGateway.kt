package com.jervis.service.gateway

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.gateway.core.LlmCallExecutor
import com.jervis.service.gateway.processing.JsonParser
import com.jervis.service.gateway.processing.PromptBuilderService
import com.jervis.service.gateway.processing.domain.ParsedResponse
import com.jervis.service.gateway.selection.ModelCandidateSelector
import com.jervis.service.prompts.PromptRepository
import com.jervis.service.text.TokenEstimationService
import kotlinx.coroutines.CancellationException
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
) {
    private val logger = KotlinLogging.logger {}

    /**
     * The main entry point for LLM calls with strict JSON validation.
     * Uses enhanced prompt building and eliminates all fallback mechanisms.
     * Returns both think content and parsed JSON result.
     */
    suspend fun <T : Any> callLlm(
        type: PromptTypeEnum,
        responseSchema: T,
        correlationId: String,
        mappingValue: Map<String, String> = emptyMap(),
        outputLanguage: String? = null,
        backgroundMode: Boolean,
    ): ParsedResponse<T> {
        require((responseSchema is String).not()) { "Response schema must be a object not string." }
        val prompt = promptRepository.getPrompt(type)

        val mappingValues =
            buildMap {
                putAll(mappingValue)

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
                .selectCandidates(prompt.modelParams.modelType, estimatedTokens)
                .toList()

        check(candidates.isNotEmpty()) { "No LLM candidates configured for $type" }

        val maxTokenLimit = candidates.mapNotNull { it.contextLength }.maxOrNull() ?: 16000

        if (estimatedTokens > maxTokenLimit) {
            val candidateDetails =
                candidates.joinToString(", ") {
                    "${it.provider}:${it.model} (${it.contextLength ?: "unknown"} tokens)"
                }
            logger.error {
                "Context size ($estimatedTokens tokens) exceeds maximum available model capacity " +
                    "($maxTokenLimit tokens) for type: $type. Available candidates: $candidateDetails. " +
                    "Context must be reduced or a larger model must be configured."
            }
            throw IllegalStateException(
                "Context size ($estimatedTokens tokens) exceeds all available model capacities (max: $maxTokenLimit tokens). " +
                    "Reduce context size via context compaction or configure larger models for $type.",
            )
        }

        for ((index, candidate) in candidates.withIndex()) {
            val candidateContextLimit = candidate.contextLength ?: 16000

            if (estimatedTokens > candidateContextLimit) {
                logger.info {
                    "Skipping ${candidate.provider}:${candidate.model} - estimated tokens ($estimatedTokens) exceed model capacity ($candidateContextLimit)"
                }
                continue
            }

            try {
                val response =
                    llmCallExecutor.executeCall(
                        candidate,
                        systemPrompt,
                        finalUserPrompt,
                        prompt,
                        type,
                        estimatedTokens,
                        correlationId,
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
            } catch (e: CancellationException) {
                logger.info { "LLM call cancelled (background task interrupted)" }
                throw e
            } catch (e: Exception) {
                val errorMessage = e.message?.lowercase() ?: ""

                // Detect context overflow errors from providers
                val isContextOverflow =
                    errorMessage.contains("context") ||
                        errorMessage.contains("token") ||
                        errorMessage.contains("too long") ||
                        errorMessage.contains("maximum") ||
                        errorMessage.contains("exceeds")

                if (isContextOverflow) {
                    logger.warn {
                        "Context overflow detected for ${candidate.provider}:${candidate.model}. " +
                            "Attempting failover to larger model if available."
                    }
                    // Continue to the next candidate (should be a larger model)
                    if (index == candidates.size - 1) {
                        throw IllegalStateException(
                            "Context overflow on final candidate ${candidate.provider}:${candidate.model}. " +
                                "No larger models configured for $type.",
                            e,
                        )
                    }
                    continue
                }

                logger.error { "LLM call failed for provider=${candidate.provider} model=${candidate.model}: ${e.message}" }

                // Continue to the next candidate on any error
                if (index == candidates.size - 1) {
                    throw IllegalStateException("All LLM candidates failed for $type. Last error: ${e.message}", e)
                }
            }
        }

        throw IllegalStateException("No successful LLM candidates for $type")
    }
}
