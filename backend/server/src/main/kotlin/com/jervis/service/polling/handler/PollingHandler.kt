package com.jervis.service.polling.handler

import com.jervis.entity.ClientDocument
import com.jervis.entity.connection.Connection
import com.jervis.service.polling.PollingResult

/**
 * Interface for type-specific polling handlers.
 *
 * Each connection type (Jira, Confluence, Email, etc.) has its own handler
 * that knows how to:
 * - Poll the external service for new/updated items
 * - Create NEW state documents in MongoDB
 * - Skip items that are already indexed
 */
interface PollingHandler {
    /**
     * Check if this handler can process the given connection type.
     */
    fun canHandle(connection: Connection): Boolean

    /**
     * Poll the external service and create NEW state documents.
     *
     * @param connection The connection configuration
     * @param clients List of clients using this connection
     * @return Polling result with counts
     */
    suspend fun poll(
        connection: Connection,
        clients: List<ClientDocument>,
    ): PollingResult
}
