package com.jervis.koog

import ai.koog.prompt.executor.model.PromptExecutor
import com.jervis.koog.executor.AnthropicPromptExecutor
import com.jervis.koog.executor.GooglePromptExecutor
import com.jervis.koog.executor.OllamaPromptExecutor
import com.jervis.koog.executor.OllamaQualifierPromptExecutor
import com.jervis.koog.executor.OpenAiPromptExecutor
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class KoogPromptExecutorFactory(
    private val ollamaPromptExecutor: OllamaPromptExecutor,
    private val ollamaQualifierPromptExecutor: OllamaQualifierPromptExecutor,
    private val anthropicPromptExecutor: AnthropicPromptExecutor,
    private val openAiPromptExecutor: OpenAiPromptExecutor,
    private val googlePromptExecutor: GooglePromptExecutor,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Get PromptExecutor bean for a specified provider.
     * Provider names: OLLAMA, OLLAMA_QUALIFIER, ANTHROPIC, OPENAI, GOOGLE
     */
    fun getExecutor(providerName: String): PromptExecutor {
        logger.info { "Getting PromptExecutor bean for provider=$providerName" }

        return when (providerName) {
            "OLLAMA" -> ollamaPromptExecutor

            "OLLAMA_QUALIFIER" -> ollamaQualifierPromptExecutor

            "ANTHROPIC" -> anthropicPromptExecutor

            "OPENAI" -> openAiPromptExecutor

            "GOOGLE" -> googlePromptExecutor

            else -> throw IllegalArgumentException(
                "Unknown provider: $providerName. Available: OLLAMA, OLLAMA_QUALIFIER, ANTHROPIC, OPENAI, GOOGLE",
            )
        }
    }
}
