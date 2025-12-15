package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "coding-tools")
data class CodingToolsProperties(
    val aider: AiderConfig,
    val openhands: OpenHandsConfig,
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
