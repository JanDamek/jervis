package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "retry")
data class RetryProperties(
    val ktor: KtorRetry,
) {
    data class KtorRetry(
        val maxAttempts: Int,
        val initialBackoffMillis: Long,
        val maxBackoffMillis: Long,
    )
}
