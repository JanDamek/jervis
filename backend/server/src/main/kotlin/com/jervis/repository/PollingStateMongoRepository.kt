package com.jervis.repository

import com.jervis.entity.polling.PollingStateDocument
import com.jervis.types.ConnectionId
import com.jervis.types.PollingStateId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for PollingStateDocument.
 *
 * Provides atomic operations for polling state management.
 * Each handler maintains its own state per connection.
 */
@Repository
interface PollingStateMongoRepository : CoroutineCrudRepository<PollingStateDocument, PollingStateId> {
    /**
     * Find polling state for a specific connection and handler combination.
     * Returns null if no state exists yet (first poll).
     */
    suspend fun findByConnectionIdAndHandlerType(connectionId: ConnectionId, handlerType: String): PollingStateDocument?

    /**
     * Delete all polling states for a specific connection.
     * Used when a connection is deleted.
     */
    suspend fun deleteByConnectionId(connectionId: ConnectionId)
}
