package com.jervis.dto.confluence

import java.time.Instant

/**
 * DTO for creating/updating Confluence account.
 * Used by UI for OAuth flow and configuration.
 */
data class ConfluenceAccountCreateDto(
    val clientId: String,
    val projectId: String? = null,
    val cloudId: String,
    val siteName: String,
    val siteUrl: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenExpiresAt: Instant? = null,
    val spaceKeys: List<String> = emptyList(),
)
