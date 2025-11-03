package com.jervis.entity.jira

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB entity representing Jira Cloud connection and selections for a client.
 * Secrets (tokens) are stored as plain strings for now; avoid logging them anywhere.
 */
@Document(collection = "jira_connections")
@CompoundIndexes(
    CompoundIndex(name = "client_tenant_unique", def = "{'clientId': 1, 'tenant': 1}", unique = true),
)
data class JiraConnectionDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val clientId: ObjectId,
    /** Atlassian cloud tenant hostname, e.g. example.atlassian.net */
    val tenant: String,
    /** Account email used for API token authentication */
    val email: String? = null,
    /** Short-lived access token */
    val accessToken: String,
    /** Long-lived refresh token (offline access) */
    val refreshToken: String,
    /** When the access token expires (epoch millis) */
    val expiresAt: Instant,
    /** Preferred user accountId for deep indexing focus */
    val preferredUser: String? = null,
    /** Main Jira board id if selected */
    val mainBoard: Long? = null,
    /** Primary project key for this client */
    val primaryProject: String? = null,
    @Indexed
    val updatedAt: Instant = Instant.now(),
)
