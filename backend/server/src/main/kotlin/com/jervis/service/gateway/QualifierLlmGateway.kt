package com.jervis.service.gateway

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jervis.configuration.prompts.CreativityLevel
import com.jervis.configuration.prompts.ModelParams
import com.jervis.configuration.prompts.PromptGenericConfig
import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelTypeEnum
import com.jervis.service.gateway.core.LlmCallExecutor
import com.jervis.service.gateway.processing.PromptBuilderService
import com.jervis.service.gateway.selection.ModelCandidateSelector
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Simplified gateway for task pre-qualification.
 * Reuses core LLM infrastructure (LlmCallExecutor, PromptBuilderService, ModelCandidateSelector).
 * The only implementation detail difference:
 * - Uses QUALIFIER ModelType (routes to CPU endpoint via OllamaClient.selectWebClient)
 * - Fixed LOW creativity
 * - Parses decision from JSON response
 */
@Service
class QualifierLlmGateway(
    private val llmCallExecutor: LlmCallExecutor,
    private val modelCandidateSelector: ModelCandidateSelector,
    private val promptBuilderService: PromptBuilderService,
) {
    /**
     * Qualifies a task using template-based prompts with dynamic variable substitution.
     * Reuses shared LLM infrastructure - only ModelType.QUALIFIER makes it route to CPU endpoint.
     */
    suspend fun qualify(
        systemPromptTemplate: String,
        userPromptTemplate: String,
        mappingValues: Map<String, String>,
        correlationId: String,
        promptType: PromptTypeEnum = PromptTypeEnum.EMAIL_QUALIFIER,
    ): QualifierDecision {
        return try {
            val promptConfig =
                PromptGenericConfig(
                    systemPrompt = systemPromptTemplate,
                    userPrompt = userPromptTemplate,
                    modelParams =
                        ModelParams(
                            modelType = ModelTypeEnum.QUALIFIER,
                            creativityLevel = CreativityLevel.LOW,
                        ),
                )

            // Use PromptBuilderService to fill templates (shared with the main LLM flow)
            val filledSystemPrompt = promptBuilderService.buildSystemPrompt(promptConfig, mappingValues)
            val filledUserPrompt =
                promptBuilderService.buildUserPrompt(
                    prompt = promptConfig,
                    mappingValues = mappingValues,
                    outputLanguage = null,
                    responseSchema = QualifierResult(),
                )

            // Estimate tokens based on prompt size (rough: 1 token ≈ 4 chars)
            val totalPromptChars = filledSystemPrompt.length + filledUserPrompt.length
            val estimatedTokens = (totalPromptChars / 4) + 500 // +500 for response buffer

            if (estimatedTokens > 32000) {
                logger.warn { "Qualifier prompt too large: $estimatedTokens tokens (max 32k) - delegating to strong model" }
                return QualifierDecision.Delegate
            }

            logger.trace { "Qualifier call: estimatedTokens=$estimatedTokens (prompt $totalPromptChars chars)" }

            // Select candidates with QUALIFIER type (shared model selection logic)
            val candidates =
                modelCandidateSelector
                    .selectCandidates(ModelTypeEnum.QUALIFIER, quickModeOnly = false, estimatedTokens = estimatedTokens)
                    .toList()

            if (candidates.isEmpty()) {
                logger.warn { "No QUALIFIER model configured, defaulting to DELEGATE" }
                return QualifierDecision.Delegate
            }

            // Execute call using shared LlmCallExecutor (will route to CPU endpoint via OllamaClient)
            val response =
                llmCallExecutor.executeCall(
                    candidate = candidates.first(),
                    systemPrompt = filledSystemPrompt,
                    userPrompt = filledUserPrompt,
                    prompt = promptConfig,
                    promptType = promptType,
                    estimatedTokens = estimatedTokens,
                    correlationId = correlationId,
                    backgroundMode = true,
                )

            val mapper = jacksonObjectMapper()

            // Strip markdown code fences if present (some models ignore instructions)
            val cleanedAnswer =
                response.answer
                    .trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

            val parsed = mapper.readValue(cleanedAnswer, QualifierResult::class.java)

            when (parsed.decision?.lowercase()) {
                "discard" -> {
                    logger.debug { "Qualifier decision: DISCARD - ${parsed.reason}" }
                    QualifierDecision.Discard
                }

                "delegate" -> {
                    logger.debug { "Qualifier decision: DELEGATE - ${parsed.reason}" }
                    QualifierDecision.Delegate
                }

                else -> {
                    logger.warn { "Unknown qualifier decision: ${parsed.decision}, defaulting to DELEGATE" }
                    QualifierDecision.Delegate
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Qualifier failed, defaulting to DELEGATE" }
            QualifierDecision.Delegate
        }
    }

    @Serializable
    data class QualifierResult(
        val decision: String? = "",
        val reason: String? = "",
    )

    /**
     * Generic qualifier for custom result types (e.g., link safety qualification).
     * Returns parsed result of type T.
     */
    suspend fun <T : Any> qualifyGeneric(
        systemPrompt: String,
        userPrompt: String,
        resultClass: Class<T>,
        correlationId: String,
        promptType: PromptTypeEnum = PromptTypeEnum.LINK_QUALIFIER,
    ): T {
        val promptConfig =
            PromptGenericConfig(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                modelParams =
                    ModelParams(
                        modelType = ModelTypeEnum.QUALIFIER,
                        creativityLevel = CreativityLevel.LOW,
                    ),
            )

        // Estimate tokens dynamically
        val totalPromptChars = systemPrompt.length + userPrompt.length
        val estimatedTokens = (totalPromptChars / 4) + 500

        val candidates =
            modelCandidateSelector
                .selectCandidates(ModelTypeEnum.QUALIFIER, quickModeOnly = false, estimatedTokens = estimatedTokens)
                .toList()

        if (candidates.isEmpty()) {
            throw IllegalStateException("No QUALIFIER model configured")
        }

        val response =
            llmCallExecutor.executeCall(
                candidate = candidates.first(),
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                prompt = promptConfig,
                promptType = promptType,
                estimatedTokens = estimatedTokens,
                correlationId = correlationId,
                backgroundMode = true,
            )

        val mapper = jacksonObjectMapper()

        // Strip markdown code fences if present
        val cleanedAnswer =
            response.answer
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

        return mapper.readValue(cleanedAnswer, resultClass)
    }

    sealed class QualifierDecision {
        data object Discard : QualifierDecision()

        data object Delegate : QualifierDecision()
    }
}
