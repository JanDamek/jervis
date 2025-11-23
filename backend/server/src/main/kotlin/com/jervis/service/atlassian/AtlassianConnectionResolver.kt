package com.jervis.service.atlassian

import com.jervis.entity.atlassian.AtlassianConnectionDocument
import com.jervis.repository.AtlassianConnectionMongoRepository
import com.jervis.repository.ClientMongoRepository
import com.jervis.repository.ProjectMongoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Service for resolving Atlassian connections from client/project references.
 *
 * Architecture:
 * - ClientDocument.atlassianConnectionId → AtlassianConnectionDocument
 * - ProjectDocument.atlassianConnectionId → AtlassianConnectionDocument (overrides client-level)
 * - One connection can be shared by multiple clients/projects
 */
@Service
class AtlassianConnectionResolver(
    private val connectionRepository: AtlassianConnectionMongoRepository,
    private val clientRepository: ClientMongoRepository,
    private val projectRepository: ProjectMongoRepository,
) {
    /**
     * Get Atlassian connection for a client (client-level configuration).
     */
    suspend fun getConnectionForClient(clientId: ObjectId): AtlassianConnectionDocument? {
        val client = clientRepository.findById(clientId) ?: return null
        return client.atlassianConnectionId?.let { connectionRepository.findById(it) }
    }

    /**
     * Get Atlassian connection for a project.
     * Priority: project.atlassianConnectionId > client.atlassianConnectionId
     */
    suspend fun getConnectionForProject(projectId: ObjectId): AtlassianConnectionDocument? {
        val project = projectRepository.findById(projectId) ?: return null

        // Try project-level override first
        project.atlassianConnectionId?.let { connectionId ->
            return connectionRepository.findById(connectionId)
        }

        // Fall back to client-level
        return getConnectionForClient(project.clientId)
    }

    /**
     * Get all unique Atlassian connections across all clients and projects.
     * Returns Flow of (connectionId, clientId, projectId?) tuples.
     */
    fun getAllConnectionBindings(): Flow<ConnectionBinding> = flow {
        logger.info { "ATLASSIAN_RESOLVER: Starting connection resolution..." }

        var clientBindings = 0
        var projectBindings = 0
        var clientsWithoutConnection = 0
        var projectsWithoutConnection = 0

        // Client-level connections - emit for EVERY client, even if sharing same connection
        clientRepository.findAll().collect { client ->
            if (client.atlassianConnectionId == null) {
                clientsWithoutConnection++
            } else {
                val connectionId = client.atlassianConnectionId!!
                val connection = connectionRepository.findById(connectionId)
                if (connection != null) {
                    val binding =
                        ConnectionBinding(
                            connectionId = connectionId,
                            connection = connection,
                            clientId = client.id,
                            projectId = null,
                            jiraProjectKeys = client.atlassianJiraProjects,
                            confluenceSpaceKeys = client.atlassianConfluenceSpaces,
                        )
                    logger.info {
                        "ATLASSIAN_RESOLVER: Client-level binding for client=${client.name} (${client.id.toHexString()}) " +
                            "connection=${connectionId.toHexString()} authStatus=${connection.authStatus} " +
                            "jiraProjects=${if (client.atlassianJiraProjects.isEmpty()) "ALL" else client.atlassianJiraProjects.joinToString(",")} " +
                            "confluenceSpaces=${if (client.atlassianConfluenceSpaces.isEmpty()) "ALL" else client.atlassianConfluenceSpaces.joinToString(",")}"
                    }
                    emit(binding)
                    clientBindings++
                } else {
                    logger.warn { "ATLASSIAN_RESOLVER: Client ${client.name} (${client.id.toHexString()}) references non-existent connection $connectionId" }
                }
            }
        }

        // Project-level connections (with override)
        projectRepository.findAll().collect { project ->
            if (project.atlassianConnectionId == null) {
                projectsWithoutConnection++
            } else {
                val connectionId = project.atlassianConnectionId!!
                val connection = connectionRepository.findById(connectionId)
                if (connection != null) {
                    val binding =
                        ConnectionBinding(
                            connectionId = connectionId,
                            connection = connection,
                            clientId = project.clientId,
                            projectId = project.id,
                            jiraProjectKeys = project.atlassianJiraProjects,
                            confluenceSpaceKeys = project.atlassianConfluenceSpaces,
                        )
                    logger.info {
                        "ATLASSIAN_RESOLVER: Project-level binding for project=${project.name} (${project.id.toHexString()}) " +
                            "client=${project.clientId.toHexString()} connection=${connectionId.toHexString()} " +
                            "authStatus=${connection.authStatus} " +
                            "jiraProjects=${if (project.atlassianJiraProjects.isEmpty()) "ALL" else project.atlassianJiraProjects.joinToString(",")} " +
                            "confluenceSpaces=${if (project.atlassianConfluenceSpaces.isEmpty()) "ALL" else project.atlassianConfluenceSpaces.joinToString(",")}"
                    }
                    emit(binding)
                    projectBindings++
                } else {
                    logger.warn { "ATLASSIAN_RESOLVER: Project ${project.name} (${project.id.toHexString()}) references non-existent connection $connectionId" }
                }
            }
        }

        val totalBindings = clientBindings + projectBindings
        if (totalBindings == 0) {
            logger.warn { "ATLASSIAN_RESOLVER: No active Atlassian connections found - $clientsWithoutConnection clients and $projectsWithoutConnection projects without connection configured" }
        } else {
            logger.info { "ATLASSIAN_RESOLVER: Resolved $clientBindings client-level + $projectBindings project-level = $totalBindings total bindings" }
            if (clientsWithoutConnection > 0 || projectsWithoutConnection > 0) {
                logger.info { "ATLASSIAN_RESOLVER: Skipped $clientsWithoutConnection clients and $projectsWithoutConnection projects without Atlassian connection" }
            }
        }
    }

    /**
     * Binding between a connection and a client/project with optional filters.
     */
    data class ConnectionBinding(
        val connectionId: ObjectId,
        val connection: AtlassianConnectionDocument,
        val clientId: ObjectId,
        val projectId: ObjectId?,
        val jiraProjectKeys: List<String>,
        val confluenceSpaceKeys: List<String>,
    )
}
