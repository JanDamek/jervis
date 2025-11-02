package com.jervis.service.jira

import com.jervis.domain.jira.JiraConnection

interface JiraAuthService {
    /** Begin Atlassian OAuth 2.0 (3LO with PKCE) and return authorization URL. */
    suspend fun beginCloudOauth(
        clientId: String,
        tenant: String,
        redirectUri: String,
    ): String

    /** Complete OAuth flow with authorization code and PKCE verifier, returning stored JiraConnection. */
    suspend fun completeCloudOauth(
        clientId: String,
        tenant: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): JiraConnection

    /** Ensure access token is valid; refresh if expired. Returns updated connection snapshot. */
    suspend fun ensureValidToken(conn: JiraConnection): JiraConnection
}
