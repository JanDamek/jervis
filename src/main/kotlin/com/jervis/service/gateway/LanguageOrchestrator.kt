package com.jervis.service.gateway

import com.jervis.domain.model.ModelType
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class LanguageOrchestrator(
    private val llmGateway: LlmGateway,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun generate(
        type: ModelType,
        systemPrompt: String? = null,
        userPrompt: String,
    ): String {
        val requestLang = detectLanguage(userPrompt)
        val englishPrompt = translateToEnglish(userPrompt, requestLang)
        logger.debug { "LanguageOrchestrator: detected=$requestLang" }
        return llmGateway.callLlm(
            type = type,
            systemPrompt = systemPrompt,
            userPrompt = englishPrompt,
            outputLanguage = requestLang,
        ).answer
    }

    private suspend fun detectLanguage(text: String): String {
        val prompt = "Return only ISO-639-1 code for language of the following text (no comments, no spaces):\n$text"
        val res = llmGateway.callLlm(type = ModelType.TRANSLATION, userPrompt = prompt).answer
        val code = res.trim().take(5).lowercase()
        return when {
            code.startsWith("en") -> "en"
            code.length == 2 -> code
            else -> "en"
        }
    }

    private suspend fun translateToEnglish(text: String, lang: String): String {
        if (lang == "en") return text
        val prompt = "Translate the following text to English. Return only the translation.\n$text"
        return llmGateway.callLlm(type = ModelType.TRANSLATION, userPrompt = prompt).answer.trim()
    }
}
