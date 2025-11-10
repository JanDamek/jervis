package com.jervis.dto.confluence

/**
 * DTO for Confluence Cloud account configuration.
 * Used for UI display and configuration management.
 *
 * Note: Timestamps are ISO-8601 strings for platform compatibility.
 */
data class ConfluenceAccountDto(
    val id: String,
    val clientId: String,
    val projectId: String? = null,
    val cloudId: String,
    val siteName: String,
    val siteUrl: String,
    val spaceKeys: List<String> = emptyList(),
    val isActive: Boolean = true,
    val lastPolledAt: String? = null,
    val lastSuccessfulSyncAt: String? = null,
    val lastErrorMessage: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val stats: ConfluenceAccountStatsDto? = null,
)
