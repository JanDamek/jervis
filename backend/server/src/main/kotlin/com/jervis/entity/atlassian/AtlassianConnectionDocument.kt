package com.jervis.entity.atlassian

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB entity representing Atlassian Cloud connection (Jira + Confluence) for a client.
 * Uses simple API token authentication (not OAuth).
 * Secrets (tokens) are stored as plain strings; avoid logging them anywhere.
 */
@Document(collection = "jira_connections")
@CompoundIndexes(
    CompoundIndex(name = "client_tenant_unique", def = "{'clientId': 1, 'tenant': 1}", unique = true),
    CompoundIndex(name = "auth_status_idx", def = "{'clientId': 1, 'tenant': 1, 'updatedAt': 1, 'authStatus': 1}"),
)
data class AtlassianConnectionDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val clientId: ObjectId,
    /** Atlassian cloud tenant hostname, e.g. example.atlassian.net */
    val tenant: String,
    /** Account email used for API token authentication */
    val email: String? = null,
    /** Atlassian API token (works for both Jira and Confluence) */
    val accessToken: String,

    // === Jira-specific fields ===
    /** Preferred user accountId for deep indexing focus */
    val preferredUser: String? = null,
    /** Main Jira board id if selected */
    val mainBoard: Long? = null,
    /** Primary project key for this client */
    val primaryProject: String? = null,
    /** Last successful Jira indexing sync timestamp */
    val lastSyncedAt: Instant? = null,

    // === Confluence-specific fields ===
    /** Confluence space keys to index (empty = all accessible spaces) */
    val confluenceSpaceKeys: List<String> = emptyList(),
    /** Last successful Confluence sync timestamp */
    val lastConfluenceSyncedAt: Instant? = null,
    /** Last time Confluence polling ran */
    val lastConfluencePolledAt: Instant? = null,

    // === Shared fields ===
    /** Authentication status for Atlassian connection: UNKNOWN | VALID | INVALID */
    val authStatus: String = "UNKNOWN",
    /** Last error message (auth or sync errors) */
    val lastErrorMessage: String? = null,
    /** When authentication was last tested (via UI or during API call) */
    val lastAuthCheckedAt: Instant? = null,

    @Indexed
    val updatedAt: Instant = Instant.now(),
)
