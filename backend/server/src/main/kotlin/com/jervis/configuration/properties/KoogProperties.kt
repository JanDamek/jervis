package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jervis.koog")
data class KoogProperties(
    /**
     * Maximum number of agent tool/LLM cycles for KoogWorkflowAgent.
     * Configured exclusively via application.yml according to guidelines.
     */
    val maxIterations: Int,
    /**
     * Maximum retry attempts for KoogQualifierAgent on retriable errors.
     * Default: 2 (first attempt + 1 retry)
     */
    val qualifierMaxRetries: Int = 5,
    val qualifierInitialBackoffMs: Long = 1000,
    val qualifierMaxBackoffMs: Long = 300_000,
    /**
     * Data directory for persistence and checkpoints.
     * Defaults to /Users/damekjan/git/jervis/data (from data.root-dir)
     */
    val dataDir: String = "/Users/damekjan/git/jervis/data",
    /**
     * Global timeout for LLM requests in milliseconds.
     */
    val requestTimeoutMs: Long = 900000,
)
