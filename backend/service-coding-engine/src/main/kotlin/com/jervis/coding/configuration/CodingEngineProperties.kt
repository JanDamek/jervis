package com.jervis.coding.configuration

/**
 * Configuration properties for Coding Engine.
 * Loaded from environment variables in non-Spring environment.
 */
data class CodingEngineProperties(
    val dockerHost: String,
    val sandboxImage: String,
    val maxIterations: Int,
    val ollamaBaseUrl: String,
)
