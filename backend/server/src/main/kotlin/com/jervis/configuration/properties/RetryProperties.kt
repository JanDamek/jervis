package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "retry")
data class RetryProperties(
    val webclient: WebClientRetry,
) {
    data class WebClientRetry(
        val maxAttempts: Long,
        val initialBackoffMillis: Long,
        val maxBackoffMillis: Long,
        val backoffMultiplier: Double,
    )
}
