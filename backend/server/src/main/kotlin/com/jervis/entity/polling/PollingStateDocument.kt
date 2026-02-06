package com.jervis.entity.polling

import com.jervis.common.types.ConnectionId
import com.jervis.common.types.PollingStateId
import com.jervis.dto.connection.ProviderEnum
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Polling state for a specific handler on a specific connection.
 *
 * Each handler (JIRA, CONFLUENCE, IMAP, POP3, etc.) maintains its own polling state
 * independently to avoid race conditions when multiple handlers run in parallel.
 *
 * Example states:
 * - JIRA: tracks lastSeenUpdatedAt for incremental Jira issue polling
 * - CONFLUENCE: tracks lastSeenUpdatedAt for incremental Confluence page polling
 * - IMAP: tracks lastFetchedUid for incremental email polling
 * - POP3: tracks lastFetchedMessageNumber for incremental email polling
 *
 * Architecture:
 * - One document per (connectionId, provider) pair
 * - Unique compound index ensures no duplicates
 * - Each handler writes only its own document → no race conditions
 * - Spring Data MongoDB upsert ensures atomic updates
 */
@Document(collection = "polling_states")
@CompoundIndexes(
    CompoundIndex(
        name = "connection_provider_tool_unique_idx",
        def = "{'connectionId': 1, 'provider': 1, 'tool': 1}",
        unique = true,
    ),
)
data class PollingStateDocument(
    @Id
    val id: PollingStateId = PollingStateId.generate(),
    val connectionId: ConnectionId,
    val provider: ProviderEnum,
    val tool: String = "",
    val lastFetchedUid: Long? = null,
    val lastFetchedMessageNumber: Int? = null,
    val lastSeenUpdatedAt: Instant? = null,
    val lastUpdated: Instant = Instant.now(),
)
