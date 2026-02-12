package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jervis.qualifier")
data class QualifierProperties(
    /**
     * Initial backoff delay for qualification retry (exponential: 5s→10s→20s→40s→80s→160s→300s cap).
     * Retriable errors (Ollama busy, timeout, connection) retry forever with this backoff.
     */
    val initialBackoffMs: Long = 5_000,
    /** Maximum backoff delay (5 minutes). After reaching this, retries continue at this interval. */
    val maxBackoffMs: Long = 300_000,
)
