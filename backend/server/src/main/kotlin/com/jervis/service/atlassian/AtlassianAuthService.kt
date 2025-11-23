package com.jervis.service.atlassian

import com.jervis.domain.atlassian.AtlassianConnection

interface AtlassianAuthService {
    /** Begin Atlassian OAuth 2.0 (3LO with PKCE) and return authorization URL. */
    suspend fun beginCloudOauth(
        tenant: String,
        redirectUri: String,
    ): String

    /**
     * Complete OAuth flow with authorization code and PKCE verifier.
     * Returns connection document that should be linked to client via client.atlassianConnectionId.
     */
    suspend fun completeCloudOauth(
        tenant: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): com.jervis.entity.atlassian.AtlassianConnectionDocument

    /** Test Atlassian Cloud API token by calling /rest/api/3/myself with Basic auth. */
    suspend fun testApiToken(
        tenant: String,
        email: String,
        apiToken: String,
    ): Boolean

    /**
     * Save Atlassian Cloud API token, creating or updating connection by tenant+email.
     * Returns connection document that should be linked to client via client.atlassianConnectionId.
     */
    suspend fun saveApiToken(
        tenant: String,
        email: String,
        apiToken: String,
    ): com.jervis.entity.atlassian.AtlassianConnectionDocument

    /** Ensure access token is valid; refresh if expired. Returns updated connection snapshot. */
    suspend fun ensureValidToken(conn: AtlassianConnection): AtlassianConnection
}
