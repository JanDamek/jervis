package com.jervis.teams

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Persistently stores discovered O365 resources (chats, channels, teams).
 *
 * Written by:
 * - Python browser pool (via scrape_storage.store_discovered_resources) during VLM scraping
 * - Kotlin server during on-demand discovery via ConnectionRpcImpl
 *
 * Read by:
 * - Settings UI to list available resources for project assignment
 * - Polling handler for resource filtering
 */
@Document(collection = "o365_discovered_resources")
@CompoundIndexes(
    CompoundIndex(name = "connection_resource_unique", def = "{'connectionId': 1, 'externalId': 1}", unique = true),
    CompoundIndex(name = "connection_type_idx", def = "{'connectionId': 1, 'resourceType': 1}"),
)
data class O365DiscoveredResourceDocument(
    @Id val id: ObjectId = ObjectId.get(),
    val connectionId: ConnectionId,
    val clientId: ClientId? = null,
    /** "chat", "channel", "team" */
    val resourceType: String,
    /** VLM slug ID or Graph API ID */
    val externalId: String,
    val displayName: String,
    val description: String? = null,
    /** For channels: parent team name */
    val teamName: String? = null,
    val discoveredAt: Instant = Instant.now(),
    val lastSeenAt: Instant = Instant.now(),
    /** false = disappeared from Teams */
    val active: Boolean = true,
)
