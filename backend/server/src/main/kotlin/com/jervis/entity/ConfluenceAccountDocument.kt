package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Confluence Cloud account configuration for documentation indexing.
 * Similar to EmailAccountDocument and JIRA connection, but specific to Confluence.
 *
 * Authentication:
 * - Uses Atlassian Cloud OAuth 2.0 or API token
 * - Can be shared across client OR specific to project
 * - Supports different access levels (read-only for indexing)
 *
 * Polling Strategy:
 * - Similar to EmailPollingScheduler
 * - Polls for updated pages since lastPolledAt
 * - Tracks spaces and pages in ConfluencePageDocument
 */
@Document(collection = "confluence_accounts")
@CompoundIndexes(
    CompoundIndex(name = "client_active_idx", def = "{'clientId': 1, 'isActive': 1}"),
    CompoundIndex(name = "project_active_idx", def = "{'projectId': 1, 'isActive': 1}"),
    CompoundIndex(name = "last_polled_idx", def = "{'isActive': 1, 'lastPolledAt': 1}"),
    CompoundIndex(name = "active_auth_idx", def = "{'isActive': 1, 'authStatus': 1, 'lastPolledAt': 1}"),
)
data class ConfluenceAccountDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    val clientId: ObjectId,
    val projectId: ObjectId? = null,
    val cloudId: String,
    val siteName: String,
    val siteUrl: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenExpiresAt: Instant? = null,
    val spaceKeys: List<String> = emptyList(),
    val isActive: Boolean = true,
    /**
     * Authentication status for Atlassian (Confluence) account.
     * - UNKNOWN: created or not yet validated
     * - VALID: connection tested via UI button and confirmed working
     * - INVALID: last auth failed (401/403). Indexing and API calls must NOT use this account
     *   until user fixes the settings and re-tests the connection in UI.
     */
    val authStatus: String = "UNKNOWN",
    val lastPolledAt: Instant? = null,
    val lastSuccessfulSyncAt: Instant? = null,
    /** Last error message (including auth error if applicable). */
    val lastErrorMessage: String? = null,
    /** When authentication was last tested (via UI or during API call). */
    val lastAuthCheckedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
