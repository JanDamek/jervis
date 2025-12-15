package com.jervis.service.polling.handler

import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.service.polling.PollingResult
import com.jervis.types.ClientId
import com.jervis.types.ProjectId

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
) {
    /**
     * Get all client IDs (both direct and via projects).
     */
    fun getAllClientIds(): List<ClientId> = (clients.map { it.id } + projects.map { it.clientId }).distinct()

    /**
     * Get project ID for a specific client (if connection is project-level).
     * Returns null if connection is client-level (all projects inherit).
     */
    fun getProjectId(clientId: ClientId): ProjectId? = projects.firstOrNull { it.clientId == clientId }?.id
}

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
