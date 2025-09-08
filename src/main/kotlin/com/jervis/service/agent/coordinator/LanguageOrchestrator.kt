package com.jervis.service.agent.coordinator

import com.jervis.configuration.prompts.McpToolType
import com.jervis.domain.model.ModelType
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.prompts.PromptRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

@Service
class LanguageOrchestrator(
    private val llmGateway: LlmGateway,
    private val promptRepository: PromptRepository,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    suspend fun translate(
        text: String,
        quick: Boolean,
    ): DetectionResult {
        val prompt = buildTranslationPrompt(text)
        val systemPrompt = promptRepository.getSystemPrompt(McpToolType.TRANSLATION)
        val modelParams = promptRepository.getEffectiveModelParams(McpToolType.TRANSLATION)

        val answer =
            llmGateway
                .callLlm(
                    type = ModelType.TRANSLATION,
                    systemPrompt = systemPrompt,
                    userPrompt = prompt,
                    outputLanguage = "en",
                    quick = quick,
                    modelParams = modelParams,
                ).answer
                .trim()
        val parsed = safeParse(answer)
        return DetectionResult(
            englishText = parsed.englishText,
            originalLanguage = parsed.originalLanguage,
        )
    }

    val systemPrompt = """ 
You are a English translator.
Respond ONLY with compact JSON with keys: englishText, originalLanguage, reason. No comments or extra text.
Response:
{
  "englishText": "<translated text of user request>"
  "originalLanguage": "<detected language of user request>"
}
        """

    private fun buildTranslationPrompt(text: String): String {
        val instruction =
            """
User request:
$text
JSON:
"""
        return instruction
    }

    private fun safeParse(answer: String): DetectionResult =
        try {
            json.decodeFromString(DetectionResult.serializer(), answer)
        } catch (_: Exception) {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            if (start in 0..<end) {
                runCatching {
                    json.decodeFromString(
                        DetectionResult.serializer(),
                        answer.substring(start, end + 1),
                    )
                }.getOrDefault(DetectionResult())
            } else {
                DetectionResult()
            }
        }

    @Serializable
    data class DetectionResult(
        val englishText: String = "",
        val originalLanguage: String = "",
    )
}
