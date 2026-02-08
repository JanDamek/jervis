package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jervis.qualifier")
data class QualifierProperties(
    /**
     * Maximum retry attempts for qualifier on retriable errors.
     */
    val maxRetries: Int = 5,
    val initialBackoffMs: Long = 1000,
    val maxBackoffMs: Long = 300_000,
)
