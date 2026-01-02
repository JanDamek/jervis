package com.jervis.aider.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "aider")
data class AiderProperties(
    /**
     * Workspace directory path (shared with JERVIS orchestrator).
     * Should be set to the same value as WORKSPACE_DIR environment variable.
     */
    val workspaceDir: String
)
