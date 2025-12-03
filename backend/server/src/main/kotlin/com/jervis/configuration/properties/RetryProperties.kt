package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "retry")
data class RetryProperties(
    val webclient: WebClientRetry,
    val ktor: KtorRetry,
) {
    data class WebClientRetry(
        val maxAttempts: Long,
        val initialBackoffMillis: Long,
        val maxBackoffMillis: Long,
        val backoffMultiplier: Double,
    )

    data class KtorRetry(
        val maxAttempts: Int,
        val initialBackoffMillis: Long,
        val maxBackoffMillis: Long,
    )
}
