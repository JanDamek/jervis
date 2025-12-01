package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Ollama general properties.
 */
@ConfigurationProperties(prefix = "ollama")
data class OllamaProperties(
    val keepAlive: KeepAliveProps,
) {
    data class KeepAliveProps(
        val default: String,
    )
}
