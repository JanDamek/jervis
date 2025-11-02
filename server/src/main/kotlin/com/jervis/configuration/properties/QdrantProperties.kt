package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "qdrant")
data class QdrantProperties(
    val host: String,
    val port: Int,
)
