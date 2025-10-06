package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "retry")
class RetryProperties {
    var webclient: WebClientRetry = WebClientRetry()

    data class WebClientRetry(
        var maxAttempts: Long = 5,
        var initialBackoffMillis: Long = 1000,
        var maxBackoffMillis: Long = 30000,
        var backoffMultiplier: Double = 2.0,
    )
}
