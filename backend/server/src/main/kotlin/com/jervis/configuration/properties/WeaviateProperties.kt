package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "weaviate")
data class WeaviateProperties(
    val enabled: Boolean = false,
    val host: String = "localhost",
    val port: Int = 8080,
    val scheme: String = "http",
    val grpcPort: Int = 50051,
    val hybridSearch: HybridSearchProperties = HybridSearchProperties(),
) {
    data class HybridSearchProperties(
        val enabled: Boolean = false,
        val alpha: Double = 0.75,
    )
}
