package com.jervis.repository

import com.jervis.common.types.ConnectionId
import com.jervis.common.types.PollingStateId
import com.jervis.entity.polling.PollingStateDocument
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for PollingStateDocument.
 *
 * Provides atomic operations for polling state management.
 * Each handler maintains its own state per connection.
 */
@Repository
interface PollingStateRepository : CoroutineCrudRepository<PollingStateDocument, PollingStateId> {
    /**
     * Find polling state for a specific connection, provider and tool combination.
     * Returns null if no state exists yet (first poll).
     */
    suspend fun findByConnectionIdAndProviderAndTool(
        connectionId: ConnectionId,
        provider: com.jervis.dto.connection.ProviderEnum,
        tool: String,
    ): PollingStateDocument?

    /**
     * Delete all polling states for a specific connection.
     * Used when a connection is deleted.
     */
    suspend fun deleteByConnectionId(connectionId: ConnectionId)
}
