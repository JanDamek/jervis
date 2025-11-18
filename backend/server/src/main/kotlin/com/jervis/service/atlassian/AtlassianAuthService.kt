package com.jervis.service.atlassian

import com.jervis.domain.atlassian.AtlassianConnection

interface AtlassianAuthService {
    /** Begin Atlassian OAuth 2.0 (3LO with PKCE) and return authorization URL. */
    suspend fun beginCloudOauth(
        clientId: String,
        tenant: String,
        redirectUri: String,
    ): String

    /** Complete OAuth flow with authorization code and PKCE verifier, returning stored AtlassianConnection. */
    suspend fun completeCloudOauth(
        clientId: String,
        tenant: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): AtlassianConnection

    /** Test Atlassian Cloud API token by calling /rest/api/3/myself with Basic auth. */
    suspend fun testApiToken(
        tenant: String,
        email: String,
        apiToken: String,
    ): Boolean

    /** Save Atlassian Cloud API token for client and tenant, returning stored connection snapshot. */
    suspend fun saveApiToken(
        clientId: String,
        tenant: String,
        email: String,
        apiToken: String,
    ): AtlassianConnection

    /** Ensure access token is valid; refresh if expired. Returns updated connection snapshot. */
    suspend fun ensureValidToken(conn: AtlassianConnection): AtlassianConnection
}
