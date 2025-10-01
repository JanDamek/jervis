package com.jervis.service.agent.coordinator

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.gateway.core.LlmGateway
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Service

@Service
class LanguageOrchestrator(
    private val llmGateway: LlmGateway,
) {
    suspend fun translate(
        text: String,
        quick: Boolean,
    ): DetectionResult {
        val result =
            llmGateway
                .callLlm(
                    type = PromptTypeEnum.PLANNING_ANALYZE_QUESTION,
                    mappingValue = mapOf("userText" to text),
                    quick = quick,
                    responseSchema = DetectionResult(),
                )

        return result
    }

    @Serializable
    data class DetectionResult(
        val englishText: String = "The text in English goes here.",
        val originalLanguage: String = "Czech",
        val contextName: String = "New Context",
        val questionChecklist: List<String> =
            listOf(
                "What is the main goal of this scenario?",
                "Who are the key stakeholders?",
            ),
        val investigationGuidance: List<String> =
            listOf(
                "Review historical data for similar scenarios.",
                "Consult with domain experts.",
            ),
    )
}
