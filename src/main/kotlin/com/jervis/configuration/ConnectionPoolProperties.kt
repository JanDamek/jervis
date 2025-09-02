package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "connection-pools")
class ConnectionPoolProperties {
    var webclient: WebClientConnectionPool = WebClientConnectionPool()
    var qdrant: QdrantConnectionPool = QdrantConnectionPool()
    var embedding: EmbeddingConnectionPool = EmbeddingConnectionPool()

    data class WebClientConnectionPool(
        var maxConnections: Int = 500,
        var maxIdleTimeMinutes: Long = 30,
        var maxLifeTimeMinutes: Long = 60,
        var pendingAcquireTimeoutMinutes: Long = 30,
        var evictInBackgroundMinutes: Long = 60,
        var pendingAcquireMaxCount: Int = 1000,
    )

    data class QdrantConnectionPool(
        var maxConnections: Int = 50,
        var keepAliveTimeoutMinutes: Long = 30,
        var connectionTimeoutMinutes: Long = 5,
    )

    data class EmbeddingConnectionPool(
        var enableRateLimiting: Boolean = true,
        var defaultMaxConcurrentRequests: Int = 10,
        var providers: Map<String, ProviderConnectionPool> = emptyMap(),
    )

    data class ProviderConnectionPool(
        var maxConcurrentRequests: Int? = null,
    )

    // Helper methods for WebClient connection pool
    fun getWebClientMaxConnections(): Int = webclient.maxConnections

    fun getWebClientMaxIdleTime(): Duration = Duration.ofMinutes(webclient.maxIdleTimeMinutes)

    fun getWebClientMaxLifeTime(): Duration = Duration.ofMinutes(webclient.maxLifeTimeMinutes)

    fun getWebClientPendingAcquireTimeout(): Duration = Duration.ofMinutes(webclient.pendingAcquireTimeoutMinutes)

    fun getWebClientEvictInBackground(): Duration = Duration.ofMinutes(webclient.evictInBackgroundMinutes)

    // Helper methods for embedding connection pool
    fun getEmbeddingMaxConcurrentRequests(
        provider: String,
        modelMaxRequests: Int?,
    ): Int {
        val providerPool = embedding.providers[provider.lowercase()]
        return providerPool?.maxConcurrentRequests
            ?: modelMaxRequests
            ?: embedding.defaultMaxConcurrentRequests
    }

    fun isEmbeddingRateLimitingEnabled(): Boolean = embedding.enableRateLimiting
}
