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
    ): DetectionResult =
        llmGateway
            .callLlm(
                type = PromptTypeEnum.TRANSLATION,
                userPrompt = text,
                quick = quick,
                responseSchema = DetectionResult(),
            )

    @Serializable
    data class DetectionResult(
        val englishText: String = "",
        val originalLanguage: String = "",
        val contextName: String = "New Context",
    )
}
