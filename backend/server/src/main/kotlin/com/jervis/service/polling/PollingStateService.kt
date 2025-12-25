package com.jervis.service.polling

import com.jervis.entity.polling.PollingStateDocument
import com.jervis.repository.PollingStateRepository
import com.jervis.types.ConnectionId
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for managing polling states.
 *
 * Each handler (JIRA, CONFLUENCE, IMAP, POP3, etc.) maintains its own polling state
 * independently per connection. This prevents race conditions when multiple handlers
 * run in parallel for the same connection (e.g., Jira + Confluence for Atlassian).
 *
 * Architecture:
 * - One PollingStateDocument per (connectionId, handlerType) pair
 * - Atomic updates via Spring Data MongoDB save() with unique compound index
 * - No race conditions - each handler writes only its own document
 */
@Service
class PollingStateService(
    private val repository: PollingStateRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Get polling state for a specific handler on a specific connection.
     * Returns null if no state exists yet (first poll).
     */
    suspend fun getState(
        connectionId: ConnectionId,
        handlerType: String,
    ): PollingStateDocument? = repository.findByConnectionIdAndHandlerType(connectionId, handlerType)

    /**
     * Update polling state with lastSeenUpdatedAt (for HTTP handlers like Jira, Confluence).
     * Creates new state if it doesn't exist.
     */
    suspend fun updateWithTimestamp(
        connectionId: ConnectionId,
        handlerType: String,
        lastSeenUpdatedAt: Instant,
    ): PollingStateDocument {
        val existing = repository.findByConnectionIdAndHandlerType(connectionId, handlerType)
        val updated =
            existing?.copy(
                lastSeenUpdatedAt = lastSeenUpdatedAt,
                lastUpdated = Instant.now(),
            ) ?: PollingStateDocument(
                connectionId = connectionId,
                handlerType = handlerType,
                lastSeenUpdatedAt = lastSeenUpdatedAt,
                lastUpdated = Instant.now(),
            )

        val saved = repository.save(updated)
        logger.debug { "Updated polling state: connectionId=$connectionId, handler=$handlerType, lastSeenUpdatedAt=$lastSeenUpdatedAt" }
        return saved
    }

    /**
     * Update polling state with lastFetchedUid (for IMAP handler).
     * Creates new state if it doesn't exist.
     */
    suspend fun updateWithUid(
        connectionId: ConnectionId,
        handlerType: String,
        lastFetchedUid: Long,
    ): PollingStateDocument {
        val existing = repository.findByConnectionIdAndHandlerType(connectionId, handlerType)
        val updated =
            existing?.copy(
                lastFetchedUid = lastFetchedUid,
                lastUpdated = Instant.now(),
            ) ?: PollingStateDocument(
                connectionId = connectionId,
                handlerType = handlerType,
                lastFetchedUid = lastFetchedUid,
                lastUpdated = Instant.now(),
            )

        val saved = repository.save(updated)
        logger.debug { "Updated polling state: connectionId=$connectionId, handler=$handlerType, lastFetchedUid=$lastFetchedUid" }
        return saved
    }

    /**
     * Update polling state with lastFetchedMessageNumber (for POP3 handler).
     * Creates new state if it doesn't exist.
     */
    suspend fun updateWithMessageNumber(
        connectionId: ConnectionId,
        handlerType: String,
        lastFetchedMessageNumber: Int,
    ): PollingStateDocument {
        val existing = repository.findByConnectionIdAndHandlerType(connectionId, handlerType)
        val updated =
            existing?.copy(
                lastFetchedMessageNumber = lastFetchedMessageNumber,
                lastUpdated = Instant.now(),
            ) ?: PollingStateDocument(
                connectionId = connectionId,
                handlerType = handlerType,
                lastFetchedMessageNumber = lastFetchedMessageNumber,
                lastUpdated = Instant.now(),
            )

        val saved = repository.save(updated)
        logger.debug {
            "Updated polling state: connectionId=$connectionId, handler=$handlerType, lastFetchedMessageNumber=$lastFetchedMessageNumber"
        }
        return saved
    }

    /**
     * Delete all polling states for a specific connection.
     * Used when a connection is deleted.
     */
    suspend fun deleteByConnectionId(connectionId: ConnectionId) {
        repository.deleteByConnectionId(connectionId)
        logger.debug { "Deleted all polling states for connectionId=$connectionId" }
    }
}
