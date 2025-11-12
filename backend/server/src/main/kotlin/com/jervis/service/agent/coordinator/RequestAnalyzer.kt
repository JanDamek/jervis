package com.jervis.service.agent.coordinator

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.gateway.core.LlmGateway
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Analyzes incoming requests and produces a structured view used by the planner.
 *
 * Behavior by mode:
 * - Foreground (backgroundMode=false): Performs full LLM analysis (language detection, checklist, initial RAG queries).
 * - Background (backgroundMode=true): Pass through mode for pending background tasks – no LLM call, no heuristics.
 *   The input text is treated as already normalized instruction, and an optional customGoal is placed into the checklist
 *   to drive the planner deterministically for the specific background task type.
 */
@Service
class RequestAnalyzer(
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Analyze a request and extract structured information for planning.
     *
     * Foreground mode runs a full LLM analysis. Background mode is a pass-through for pending tasks
     * where the agent must deterministically resolve the task using a content-only policy.
     *
     * @param text User request or background task content
     * @param quick Hint for LLM to prefer a faster path if available
     * @param backgroundMode If true, returns pass-through analysis without contacting LLM
     * @param goalPrompt Optional single prompt used for background tasks; mapped into a checklist when provided
     * @return Analysis result used by planner
     */
    suspend fun analyze(
        text: String,
        quick: Boolean,
        backgroundMode: Boolean,
        goalPrompt: String? = null,
    ): AnalysisResult {
        if (backgroundMode) {
            logger.debug { "REQUEST_ANALYZER: Background mode - pass-through analysis (no LLM)" }
            return createBackgroundPassThroughAnalysis(text, goalPrompt)
        }

        logger.debug { "REQUEST_ANALYZER: Performing full LLM analysis" }
        val result =
            llmGateway
                .callLlm(
                    type = PromptTypeEnum.PLANNING_ANALYZE_QUESTION,
                    mappingValue = mapOf("userText" to text),
                    quick = quick,
                    responseSchema = AnalysisResult(),
                    backgroundMode = false,
                )

        return result.result
    }

    /**
     * Create pass-through analysis for background pending tasks.
     *
     * No LLM call is made. The input [text] becomes the English instruction and
     * the optional [customGoal] is injected as a single-item checklist to steer planning.
     */
    private fun createBackgroundPassThroughAnalysis(
        text: String,
        customGoal: String? = null,
    ): AnalysisResult =
        AnalysisResult(
            englishText = text,
            originalLanguage = "EN",
            contextName = "Background Task",
            questionChecklist = customGoal?.let { listOf(it) } ?: emptyList(),
            initialRagQueries = emptyList(),
        )

    @Serializable
    data class AnalysisResult(
        val englishText: String = "",
        val originalLanguage: String = "",
        val contextName: String = "",
        val questionChecklist: List<String> = emptyList(),
        val initialRagQueries: List<String> = emptyList(),
    )
}
