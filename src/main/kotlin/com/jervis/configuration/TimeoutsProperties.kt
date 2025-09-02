package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "timeouts")
class TimeoutsProperties {
    var embedding: EmbeddingTimeouts = EmbeddingTimeouts()
    var joern: JoernTimeouts = JoernTimeouts()
    var webclient: WebClientTimeouts = WebClientTimeouts()
    var qdrant: QdrantTimeouts = QdrantTimeouts()
    var mcp: McpTimeouts = McpTimeouts()

    data class EmbeddingTimeouts(
        var requestTimeoutMinutes: Long = 10,
        var connectionTimeoutMinutes: Long = 5,
        var providers: Map<String, ProviderTimeouts> = emptyMap(),
    )

    data class ProviderTimeouts(
        var requestTimeoutMinutes: Long? = null,
        var connectionTimeoutMinutes: Long? = null,
    )

    data class JoernTimeouts(
        var processTimeoutMinutes: Long = 30,
        var helpCommandTimeoutMinutes: Long = 30,
        var scanTimeoutMinutes: Long = 30,
        var scriptTimeoutMinutes: Long = 30,
        var parseTimeoutMinutes: Long = 30,
        var versionTimeoutMinutes: Long = 30,
    )

    data class WebClientTimeouts(
        var connectTimeoutMinutes: Long = 30,
        var responseTimeoutMinutes: Long = 30,
        var pendingAcquireTimeoutMinutes: Long = 30,
    )

    data class QdrantTimeouts(
        var operationTimeoutMinutes: Long = 30,
    )

    data class McpTimeouts(
        var joernToolTimeoutSeconds: Long = 1800,
        var terminalToolTimeoutSeconds: Long = 1800,
    )

    // Helper methods for embedding timeouts
    fun getEmbeddingRequestTimeout(provider: String): Duration {
        val providerTimeouts = embedding.providers[provider.lowercase()]
        val minutes = providerTimeouts?.requestTimeoutMinutes ?: embedding.requestTimeoutMinutes
        return Duration.ofMinutes(minutes)
    }

    fun getEmbeddingConnectionTimeout(provider: String): Duration {
        val providerTimeouts = embedding.providers[provider.lowercase()]
        val minutes = providerTimeouts?.connectionTimeoutMinutes ?: embedding.connectionTimeoutMinutes
        return Duration.ofMinutes(minutes)
    }
}
