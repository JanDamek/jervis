package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Immutable configuration for Ollama preloading/keep-alive.
 * Follows guidelines: data class with val fields, no defaults, non-null.
 */
@ConfigurationProperties(prefix = "preload.ollama")
data class PreloadOllamaProperties(
    val gpu: ConcurrencyProps,
    val cpu: ConcurrencyProps,
    val llm: LlmProps,
    val embed: EmbedProps,
) {
    data class ConcurrencyProps(
        val concurrency: Int,
    )

    data class LlmProps(
        val keepAlive: String,
        val refreshSafetyFactor: Double,
    )

    data class EmbedProps(
        val concurrency: Int,
        val keepAlive: String,
        val refreshSafetyFactor: Double,
    )
}
