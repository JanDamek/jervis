package com.jervis.koog.executor

import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import io.ktor.client.plugins.HttpTimeoutConfig

internal fun infiniteTimeoutConfig(): ConnectionTimeoutConfig {
    val infinite = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    return ConnectionTimeoutConfig(
        requestTimeoutMillis = infinite,
        connectTimeoutMillis = infinite,
        socketTimeoutMillis = infinite,
    )
}
