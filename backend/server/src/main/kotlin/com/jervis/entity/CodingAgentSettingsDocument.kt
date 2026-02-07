package com.jervis.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Stores API keys and configuration for coding agents (Claude, Junie, etc.).
 * One document per agent name.
 */
@Document(collection = "coding_agent_settings")
data class CodingAgentSettingsDocument(
    @Id
    val id: String? = null,
    @Indexed(unique = true)
    val agentName: String,
    val apiKey: String = "",
    /** Raw JSON content of ~/.claude/.credentials.json for OAuth (Max/Pro) auth. */
    val oauthCredentialsJson: String? = null,
)
