package com.jervis.dto.confluence

/**
 * DTO for creating/updating Confluence account.
 * Used by UI for OAuth flow and configuration.
 *
 * Note: Timestamps are ISO-8601 strings for platform compatibility.
 */
data class ConfluenceAccountCreateDto(
    val clientId: String,
    val projectId: String? = null,
    val cloudId: String,
    val siteName: String,
    val siteUrl: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenExpiresAt: String? = null,
    val spaceKeys: List<String> = emptyList(),
)
