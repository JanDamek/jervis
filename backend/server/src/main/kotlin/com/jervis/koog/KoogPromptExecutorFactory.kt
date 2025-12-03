package com.jervis.koog

import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import com.jervis.configuration.properties.EndpointProperties
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * KoogPromptExecutorFactory - Factory for creating Koog LLM PromptExecutors.
 *
 * Supports all providers defined in application.yml:
 * - OLLAMA (primary GPU endpoint)
 * - OLLAMA_QUALIFIER (CPU endpoint)
 * - OLLAMA_EMBEDDING (CPU embeddings)
 * - ANTHROPIC (Claude with an API key)
 * - OPENAI (GPT with an API key)
 * - GOOGLE (Gemini with an API key)
 *
 * Usage:
 * ```kotlin
 * val executor = factory.createExecutor("OLLAMA_QUALIFIER")
 * val agent = AIAgent(promptExecutor = executor, ...)
 * ```
 */
@Component
class KoogPromptExecutorFactory(
    private val endpointProperties: EndpointProperties,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Create PromptExecutor for a specified provider.
     * Provider names: OLLAMA, OLLAMA_QUALIFIER, OLLAMA_EMBEDDING, ANTHROPIC, OPENAI, GOOGLE
     */
    fun createExecutor(providerName: String): PromptExecutor =
        when (providerName) {
            "OLLAMA" -> {
                val baseUrl = endpointProperties.ollama.primary.baseUrl
                require(baseUrl.isNotBlank()) { "Ollama primary endpoint has blank baseUrl" }
                logger.info { "Creating Ollama PromptExecutor for provider=$providerName, url=$baseUrl" }
                simpleOllamaAIExecutor(baseUrl.removeSuffix("/"))
            }

            "OLLAMA_QUALIFIER" -> {
                val baseUrl = endpointProperties.ollama.qualifier.baseUrl
                require(baseUrl.isNotBlank()) { "Ollama qualifier endpoint has blank baseUrl" }
                logger.info { "Creating Ollama PromptExecutor for provider=$providerName, url=$baseUrl" }
                simpleOllamaAIExecutor(baseUrl.removeSuffix("/"))
            }

            "OLLAMA_EMBEDDING" -> {
                val baseUrl = endpointProperties.ollama.embedding.baseUrl
                require(baseUrl.isNotBlank()) { "Ollama embedding endpoint has blank baseUrl" }
                logger.info { "Creating Ollama PromptExecutor for provider=$providerName, url=$baseUrl" }
                simpleOllamaAIExecutor(baseUrl.removeSuffix("/"))
            }

            "ANTHROPIC" -> {
                val apiKey = endpointProperties.anthropic.apiKey
                require(apiKey.isNotBlank()) { "Anthropic API key is blank. Set ANTHROPIC_API_KEY environment variable." }
                logger.info { "Creating Anthropic PromptExecutor for provider=$providerName" }
                // Note: Koog simpleAnthropicExecutor only accepts apiKey, baseUrl not supported
                simpleAnthropicExecutor(apiKey)
            }

            "OPENAI" -> {
                val apiKey = endpointProperties.openai.apiKey
                require(apiKey.isNotBlank()) { "OpenAI API key is blank. Set OPENAI_API_KEY environment variable." }
                logger.info { "Creating OpenAI PromptExecutor for provider=$providerName" }
                // Note: Koog simpleOpenAIExecutor only accepts apiToken, baseUrl not supported
                simpleOpenAIExecutor(apiKey)
            }

            "GOOGLE" -> {
                val apiKey = endpointProperties.google.apiKey
                require(apiKey.isNotBlank()) { "Google API key is blank. Set GOOGLE_API_KEY environment variable." }
                logger.info { "Creating Google PromptExecutor for provider=$providerName" }
                // Note: Koog simpleGoogleAIExecutor only accepts apiKey, baseUrl not supported
                simpleGoogleAIExecutor(apiKey)
            }

            else -> {
                throw IllegalArgumentException("Unknown provider: $providerName")
            }
        }
}
