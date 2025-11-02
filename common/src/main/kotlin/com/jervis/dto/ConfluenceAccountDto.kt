package com.jervis.dto

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

data class ConfluenceAccountStatsDto(
    val totalPages: Long,
    val indexedPages: Long,
    val newPages: Long,
    val failedPages: Long,
    val totalSpaces: Int,
)

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

/**
 * DTO for updating Confluence account settings.
 */
data class ConfluenceAccountUpdateDto(
    val spaceKeys: List<String>? = null,
    val isActive: Boolean? = null,
)

/**
 * DTO for Confluence page display in UI.
 */
data class ConfluencePageDto(
    val id: String,
    val accountId: String,
    val pageId: String,
    val spaceKey: String,
    val title: String,
    val url: String,
    val lastKnownVersion: Int,
    val state: String, // NEW, INDEXED, FAILED
    val parentPageId: String? = null,
    val internalLinksCount: Int,
    val externalLinksCount: Int,
    val childPagesCount: Int,
    val lastModifiedBy: String? = null,
    val lastModifiedAt: Instant? = null,
    val lastIndexedAt: Instant? = null,
    val errorMessage: String? = null,
)

/**
 * DTO for triggering manual Confluence sync.
 */
data class ConfluenceSyncRequestDto(
    val accountId: String,
)

/**
 * DTO for Confluence OAuth callback response.
 * Contains access token and site information from Atlassian.
 */
data class ConfluenceOAuthCallbackDto(
    val code: String,
    val state: String,
)

/**
 * DTO for Confluence OAuth access token exchange response.
 */
data class ConfluenceOAuthTokenDto(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresIn: Int,
    val scope: String,
    val cloudId: String,
    val siteName: String,
    val siteUrl: String,
)
