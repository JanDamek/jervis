package com.jervis.coding.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "coding-engine")
data class CodingEngineProperties(
    val dataRootDir: String,
    val dockerHost: String,
    val sandboxImage: String,
    val maxIterations: Int,
    val ollamaBaseUrl: String,
)
