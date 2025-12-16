package com.jervis.service.polling.handler

import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.service.polling.PollingResult

/**
 * Context for polling - contains clients and projects using a connection.
 *
 * ConnectionDocument assignment logic:
 * - Client-level: ConnectionDocument in ClientDocument.connectionIds → all projects inherit
 * - Project-level: ConnectionDocument in ProjectDocument.connectionIds → only that project
 *
 * This class provides unified access to both client-level and project-level connections.
 */
data class PollingContext(
    val clients: List<ClientDocument>,
    val projects: List<ProjectDocument>,
)

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
     * Check if this handler can process the given connectionDocument type.
     */
    fun canHandle(connectionDocument: ConnectionDocument): Boolean

    /**
     * Poll the external service and create NEW state documents.
     *
     * @param connectionDocument The connectionDocument configuration
     * @param context Polling context with clients and projects using this connectionDocument
     * @return Polling result with counts
     */
    suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult
}
