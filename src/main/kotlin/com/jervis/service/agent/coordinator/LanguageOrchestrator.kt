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

        return result.result
    }

    @Serializable
    data class DetectionResult(
        val englishText: String = "",
        val originalLanguage: String = "",
        val contextName: String = "",
        val questionChecklist: List<String> = listOf(""),
        val initialRagQueries: List<String> = listOf(""),
    )
}
