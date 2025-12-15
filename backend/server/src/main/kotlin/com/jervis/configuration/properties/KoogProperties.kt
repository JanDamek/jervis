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
    val qualifierMaxRetries: Int = 2,
)
