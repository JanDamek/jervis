package com.jervis.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "text.chunking")
data class TextChunkingProperties(
    val maxTokens: Int = 300,
    val overlapPercentage: Int = 10,
)
