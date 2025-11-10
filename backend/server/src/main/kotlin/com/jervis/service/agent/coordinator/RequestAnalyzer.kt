package com.jervis.service.agent.coordinator

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.gateway.core.LlmGateway
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Service for analyzing incoming user requests and extracting key information.
 *
 * For background tasks, skips LLM calls and returns simplified analysis to improve performance.
 * For foreground tasks, performs full LLM analysis including language detection, checklist generation, and RAG query extraction.
 */
@Service
class RequestAnalyzer(
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Analyzes user request and extracts structured information.
     *
     * @param text User's request text
     * @param quick Quick mode flag (passed to LLM for potentially faster response)
     * @param backgroundMode If true, skips LLM call and returns simplified analysis
     * @return Analysis result containing English text, language, checklist, and RAG queries
     */
    suspend fun analyze(
        text: String,
        quick: Boolean,
        backgroundMode: Boolean,
        customGoals: List<String> = emptyList(),
    ): AnalysisResult {
        if (backgroundMode) {
            logger.debug { "REQUEST_ANALYZER: Background mode - using simplified analysis without LLM" }
            return createSimplifiedAnalysis(text, customGoals)
        }

        logger.debug { "REQUEST_ANALYZER: Performing full LLM analysis" }
        val result =
            llmGateway
                .callLlm(
                    type = PromptTypeEnum.PLANNING_ANALYZE_QUESTION,
                    mappingValue = mapOf("userText" to text),
                    quick = quick,
                    responseSchema = AnalysisResult(),
                    backgroundMode = backgroundMode,
                )

        return result.result
    }

    /**
     * Creates simplified analysis for background tasks without calling LLM.
     * Background tasks typically have clear instructions and don't need deep analysis.
     */
    private fun createSimplifiedAnalysis(
        text: String,
        customGoals: List<String> = emptyList(),
    ): AnalysisResult =
        AnalysisResult(
            englishText = text,
            originalLanguage = "EN",
            contextName = "Background Task",
            questionChecklist = customGoals,
            initialRagQueries = emptyList(),
        )

    @Serializable
    data class AnalysisResult(
        val englishText: String = "",
        val originalLanguage: String = "",
        val contextName: String = "",
        val questionChecklist: List<String> = listOf(""),
        val initialRagQueries: List<String> = listOf(""),
    )
}
