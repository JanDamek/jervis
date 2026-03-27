package com.jervis.infrastructure.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Coding agent configuration.
 * Selection is per-project — never mix two agents on the same project.
 */
@ConfigurationProperties(prefix = "coding-tools")
data class CodingToolsProperties(
    val claude: CodingAgentConfig,
    val kilo: CodingAgentConfig,
)

data class CodingAgentConfig(
    val defaultProvider: String,
    val defaultModel: String,
    val paidProvider: String,
    val paidModel: String,
)
