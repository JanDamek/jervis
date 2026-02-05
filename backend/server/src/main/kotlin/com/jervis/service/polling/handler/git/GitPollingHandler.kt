package com.jervis.service.polling.handler.git

import com.jervis.entity.connection.ConnectionDocument
import com.jervis.service.indexing.git.state.GitCommitStateManager
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.handler.PollingContext
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Git polling handler.
 * Discovers new commits from Git repositories defined via Connections.
 */
@Component
class GitPollingHandler(
    private val stateManager: GitCommitStateManager,
    // Note: GitRemoteClient will be used by the Indexer, 
    // here we just simulate discovery or trigger a fetch.
) {

    fun canHandle(connectionDocument: ConnectionDocument): Boolean =
        connectionDocument.connectionType == ConnectionDocument.ConnectionTypeEnum.GIT

    suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        var itemsCreated = 0
        var errors = 0

        logger.info { "Polling Git connection: ${connectionDocument.name}" }

        // Process Client-level Git (Mono-repo or shared)
        context.clients.forEach { client ->
            try {
                // Here we would use GitRemoteClient to fetch and log
                // For now, this is a placeholder that triggers the logic
                logger.debug { "Polling Git for client: ${client.name}" }
                // In a real implementation, we'd fetch the git log here
            } catch (e: Exception) {
                logger.error(e) { "Failed to poll Git for client ${client.name}" }
                errors++
            }
        }

        // Process Project-level Git
        context.projects.forEach { project ->
            try {
                val repoUrl = connectionDocument.gitRemoteUrl
                if (!repoUrl.isNullOrBlank()) {
                    logger.debug { "Polling Git for project: ${project.name} at $repoUrl" }
                    // In a real implementation, we'd fetch the git log using GitRemoteClient
                    // and then call stateManager.saveNewCommits(client.id, project.id, commits)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to poll Git for project ${project.name}" }
                errors++
            }
        }

        return PollingResult(
            itemsDiscovered = itemsCreated, // Simplified
            itemsCreated = itemsCreated,
            errors = errors
        )
    }
}
