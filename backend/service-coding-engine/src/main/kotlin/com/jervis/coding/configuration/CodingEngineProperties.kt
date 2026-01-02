package com.jervis.coding.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "coding-engine")
data class CodingEngineProperties(
    /**
     * Workspace directory path (shared with JERVIS orchestrator).
     * Should be set to the same value as WORKSPACE_DIR environment variable.
     */
    val workspaceDir: String,
    val dockerHost: String,
    val sandboxImage: String,
    val maxIterations: Int,
    val ollamaBaseUrl: String,
)
