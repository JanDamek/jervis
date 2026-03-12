package com.jervis.o365gateway.config

/**
 * Configuration properties loaded from environment variables.
 */
data class O365GatewayConfig(
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080,
    val host: String = System.getenv("HOST") ?: "0.0.0.0",
    val browserPoolUrl: String = System.getenv("BROWSER_POOL_URL") ?: "http://jervis-o365-browser-pool:8090",
    val graphApiBaseUrl: String = "https://graph.microsoft.com/v1.0",
    val rateLimitPerSecond: Double = System.getenv("GRAPH_RATE_LIMIT")?.toDoubleOrNull() ?: 4.0,
)
