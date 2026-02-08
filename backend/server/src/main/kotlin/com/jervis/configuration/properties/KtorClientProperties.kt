package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Ktor HTTP client settings (prefix: `ktor`).
 *
 * Applied by [KtorClientFactory][com.jervis.configuration.KtorClientFactory] to all
 * provider HTTP clients (Ollama, Anthropic, OpenAI, Google, SearXNG).
 *
 * Note: socketTimeoutMillis=0 disables socket timeout for streaming LLM responses.
 * requestTimeoutMillis is set high (11min) because LLM generation can be slow.
 */
@ConfigurationProperties(prefix = "ktor")
data class KtorClientProperties(
    val connectionPool: KtorConnectionPool,
    val timeouts: KtorTimeouts,
    val apiVersions: ApiVersions,
    val logging: Logging,
) {
    data class KtorConnectionPool(
        val maxConnections: Int,          // Global max open connections (default 500)
        val maxConnectionsPerRoute: Int,  // Per-host limit (default 100)
        val keepAliveTimeMillis: Long,    // Connection keep-alive TTL (default 5min)
    )

    data class KtorTimeouts(
        val connectTimeoutMillis: Int,    // TCP connect timeout (default 60s)
        val requestTimeoutMillis: Long,   // Full request timeout (default 660s / 11min)
        val socketTimeoutMillis: Long,    // Socket idle timeout, 0=disabled for streaming
    )

    data class ApiVersions(
        val anthropicVersion: String,     // Anthropic API version header
    )

    data class Logging(
        val enabled: Boolean,             // Enable Ktor HTTP request/response logging
        val level: String,                // Log level (INFO, DEBUG, etc.)
    )
}
