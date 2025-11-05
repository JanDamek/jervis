package com.jervis.dto.confluence

import java.time.Instant

/**
 * DTO for Confluence Cloud account configuration.
 * Used for UI display and configuration management.
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
    val lastPolledAt: Instant? = null,
    val lastSuccessfulSyncAt: Instant? = null,
    val lastErrorMessage: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val stats: ConfluenceAccountStatsDto? = null,
)
