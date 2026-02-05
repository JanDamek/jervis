package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Binds external LLM endpoints and API keys from application.yml (and ENV) under `endpoints` prefix.
 * This replaces ad-hoc settings sources and must be the single source of truth for client configuration.
 */
@ConfigurationProperties(prefix = "endpoints")
data class EndpointProperties(
    val openai: Api,
    val anthropic: Api,
    val google: Api,
    val ollama: OllamaConfig,
    val lmStudio: Host,
    val searxng: Host,
    val aider: Host,
    val coding: Host,
    val tika: Host,
    val joern: Host,
    val whisper: Host,
    val junie: Host,
    val knowledgebase: Host,
    val providers: Map<String, String> = emptyMap(),
) {
    data class Api(
        val apiKey: String,
        val baseUrl: String,
    )

    data class Host(
        val baseUrl: String,
    )

    data class OllamaConfig(
        val primary: Host,
        val qualifier: Host,
        val embedding: Host,
    )
}
