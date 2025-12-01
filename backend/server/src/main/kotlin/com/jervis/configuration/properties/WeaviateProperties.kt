package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "weaviate")
data class WeaviateProperties(
    val host: String,
    val port: Int,
    val scheme: String,
    val grpcPort: Int,
    val hybridSearch: HybridSearchProperties,
    val autoMigrate: AutoMigrateProperties,
) {
    data class HybridSearchProperties(
        val enabled: Boolean,
        val alpha: Double,
    )

    data class AutoMigrateProperties(
        val countdownSeconds: Int,
    )
}
