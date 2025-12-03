package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "connections")
data class WebClientProperties(
    val connectionPool: WebClientConnectionPool,
    val timeouts: WebClientTimeouts,
    val buffers: WebClientBuffers,
) {
    data class WebClientConnectionPool(
        var maxConnections: Int,
        var maxIdleTime: Duration,
        var maxLifeTime: Duration,
        var pendingAcquireTimeout: Duration,
        var evictInBackground: Duration,
        var pendingAcquireMaxCount: Int,
    )

    data class WebClientTimeouts(
        val connectTimeoutMillis: Int,
        val responseTimeoutMillis: Long,
    )

    data class WebClientBuffers(
        var defaultMaxInMemoryBytes: Int,
        var tikaMaxInMemoryBytes: Int,
    )
}
