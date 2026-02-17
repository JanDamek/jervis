package com.jervis.service.polling.handler

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.entity.ClientConnectionCapability
import com.jervis.entity.ClientDocument
import com.jervis.entity.ProjectConnectionCapability
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
    /**
     * The connection being polled - used for capability configuration lookups.
     */
    val connectionId: ConnectionId? = null,
) {
    /**
     * Get capability configuration for a client and specific capability type.
     * Returns null if no specific configuration exists (= index all resources).
     */
    fun getClientCapabilityConfig(
        clientId: ClientId,
        capability: ConnectionCapability,
    ): ClientConnectionCapability? {
        val connId = connectionId ?: return null
        val client = clients.find { it.id == clientId } ?: return null
        return client.connectionCapabilities.find {
            it.connectionId == connId.value && it.capability == capability
        }
    }

    /**
     * Get capability configuration for a project and specific capability type.
     * Returns null if no specific configuration exists (= use client settings or index all).
     */
    fun getProjectCapabilityConfig(
        projectId: ProjectId,
        capability: ConnectionCapability,
    ): ProjectConnectionCapability? {
        val connId = connectionId ?: return null
        val project = projects.find { it.id == projectId } ?: return null
        return project.connectionCapabilities.find {
            it.connectionId == connId.value && it.capability == capability
        }
    }

    /**
     * Determine which resources should be indexed for a client.
     * Excludes resources that are already assigned to projects (those are handled by project-level polling).
     *
     * @param clientId The client ID
     * @param capability The capability type (BUGTRACKER, WIKI, EMAIL, etc.)
     * @return ResourceFilter with indexing rules, or null if capability is disabled
     */
    fun getResourceFilter(
        clientId: ClientId,
        capability: ConnectionCapability,
    ): ResourceFilter? {
        val config = getClientCapabilityConfig(clientId, capability)

        // Capability explicitly disabled
        if (config != null && !config.enabled) return null

        // Collect resources already assigned to projects for this connection+capability
        val connId = connectionId ?: return ResourceFilter.IndexAll
        val projectClaimedResources = projects
            .filter { it.clientId == clientId }
            .flatMap { it.resources }
            .filter { it.connectionId == connId.value && it.capability == capability }
            .map { it.resourceIdentifier }
            .toSet()

        // If projects claim resources, skip client-level polling entirely.
        // Project resources define what to index — client-level would create orphan items.
        if (projectClaimedResources.isNotEmpty()) return null

        // No projects claim resources — use client config if explicit
        if (config == null) return null // No config = don't index (must be explicitly configured)
        return if (config.indexAllResources) {
            ResourceFilter.IndexAll
        } else if (config.selectedResources.isNotEmpty()) {
            ResourceFilter.IndexSelected(config.selectedResources)
        } else {
            null // Capability enabled but no resources selected = skip
        }
    }

    /**
     * Determine which resources should be indexed for a project.
     * Project config overrides client config if present.
     *
     * @param projectId The project ID
     * @param clientId The parent client ID (for fallback to client config)
     * @param capability The capability type
     * @return ResourceFilter with indexing rules, or null if capability is disabled
     */
    fun getProjectResourceFilter(
        projectId: ProjectId,
        clientId: ClientId,
        capability: ConnectionCapability,
    ): ResourceFilter? {
        val connId = connectionId ?: return null
        val project = projects.find { it.id == projectId } ?: return null

        val projectConfig = getProjectCapabilityConfig(projectId, capability)

        // Project has explicit connectionCapabilities config - use it
        if (projectConfig != null) {
            if (!projectConfig.enabled) return null
            if (projectConfig.selectedResources.isNotEmpty()) {
                return ResourceFilter.IndexSelected(projectConfig.selectedResources)
            }
            // Fall through to resource-based filter
        }

        // Use project's resources to determine which repos/resources to index
        val projectResources = project.resources.filter {
            it.connectionId == connId.value && it.capability == capability
        }
        if (projectResources.isNotEmpty()) {
            return ResourceFilter.IndexSelected(projectResources.map { it.resourceIdentifier })
        }

        // No project config and no matching resources - skip (must be explicitly configured)
        return null
    }
}

/**
 * Represents filtering rules for resource indexing.
 */
sealed class ResourceFilter {
    /** Index all available resources */
    data object IndexAll : ResourceFilter()

    /** Index only specific resources */
    data class IndexSelected(val resources: List<String>) : ResourceFilter()

    /**
     * Check if a specific resource should be indexed.
     */
    fun shouldIndex(resourceId: String): Boolean =
        when (this) {
            is IndexAll -> true
            is IndexSelected -> resources.contains(resourceId)
        }
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
     * The provider this handler is for (GitHub, GitLab, Atlassian, etc.)
     */
    val provider: com.jervis.dto.connection.ProviderEnum

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
