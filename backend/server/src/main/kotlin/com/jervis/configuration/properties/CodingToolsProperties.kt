package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "coding-tools")
data class CodingToolsProperties(
    val aider: AiderConfig,
    val openhands: OpenHandsConfig,
    val junie: JunieConfig,
    val claude: ClaudeConfig,
)

data class AiderConfig(
    val defaultProvider: String,
    val defaultModel: String,
    val paidProvider: String,
    val paidModel: String,
)

data class OpenHandsConfig(
    val defaultProvider: String,
    val defaultModel: String,
    val paidProvider: String,
    val paidModel: String,
    val ollamaBaseUrl: String,
)

data class JunieConfig(
    val defaultProvider: String,
    val defaultModel: String,
    val paidProvider: String,
    val paidModel: String,
)

data class ClaudeConfig(
    val defaultProvider: String,
    val defaultModel: String,
    val paidProvider: String,
    val paidModel: String,
)
