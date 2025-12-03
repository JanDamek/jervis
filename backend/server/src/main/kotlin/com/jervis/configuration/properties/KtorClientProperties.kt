package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ktor")
data class KtorClientProperties(
    val connectionPool: KtorConnectionPool,
    val timeouts: KtorTimeouts,
    val apiVersions: ApiVersions,
    val logging: Logging,
) {
    data class KtorConnectionPool(
        val maxConnections: Int,
        val maxConnectionsPerRoute: Int,
        val keepAliveTimeMillis: Long,
    )

    data class KtorTimeouts(
        val connectTimeoutMillis: Int,
        val requestTimeoutMillis: Long,
        val socketTimeoutMillis: Long,
    )

    data class ApiVersions(
        val anthropicVersion: String,
    )

    data class Logging(
        val enabled: Boolean,
        val level: String,
    )
}
