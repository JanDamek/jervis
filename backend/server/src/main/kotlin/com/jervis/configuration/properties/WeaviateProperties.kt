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
    val autoMigrate: AutoMigrateProperties = AutoMigrateProperties(),
) {
    data class HybridSearchProperties(
        val enabled: Boolean = false,
        val alpha: Double = 0.75,
    )

    data class AutoMigrateProperties(
        val enabled: Boolean = true, // Enable automatic schema migration
        val countdownSeconds: Int = 10, // Countdown before migration starts (abort opportunity)
        val requireConfirmation: Boolean = false, // Require manual confirmation via API endpoint
        val dryRun: Boolean = false, // Log what would be deleted without actually deleting
    )
}
