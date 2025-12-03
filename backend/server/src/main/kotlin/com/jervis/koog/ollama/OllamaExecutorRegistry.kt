package com.jervis.koog.ollama

import com.jervis.configuration.properties.EndpointProperties
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Registry of Koog Ollama executors backed by existing EndpointProperties.
 * Uses endpoints.ollama.primary and endpoints.ollama.qualifier. Fail-fast on missing baseUrls.
 */
@Component
class OllamaExecutorRegistry(
    endpointProperties: EndpointProperties,
) {
    private val logger = KotlinLogging.logger {}

    private val executors: Map<String, KoogOllamaExecutor>

    init {
        val primaryUrl = endpointProperties.ollama.primary.baseUrl
        val qualifierUrl = endpointProperties.ollama.qualifier.baseUrl

        require(primaryUrl.isNotBlank()) { "endpoints.ollama.primary.baseUrl must not be blank" }
        require(qualifierUrl.isNotBlank()) { "endpoints.ollama.qualifier.baseUrl must not be blank" }

        executors = mapOf(
            PRIMARY to KoogOllamaExecutor(name = PRIMARY, host = primaryUrl),
            QUALIFIER to KoogOllamaExecutor(name = QUALIFIER, host = qualifierUrl),
        )

        logger.info { "OllamaExecutorRegistry initialized: ${executors.keys.joinToString()}" }
    }

    fun get(name: String): KoogOllamaExecutor =
        executors[name] ?: error("Unknown Ollama executor: $name. Available: ${executors.keys}")

    fun all(): Map<String, KoogOllamaExecutor> = executors

    companion object {
        const val PRIMARY = "primary"
        const val QUALIFIER = "qualifier"
    }
}
