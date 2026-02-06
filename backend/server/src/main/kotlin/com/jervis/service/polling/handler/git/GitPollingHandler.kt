package com.jervis.service.polling.handler.git

import com.jervis.dto.connection.ConnectionCapability
import com.jervis.entity.ProjectDocument
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.service.indexing.git.GitRepositoryService
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.handler.PollingContext
import mu.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Git polling handler - clones/pulls repositories and discovers new commits.
 *
 * For each project with REPOSITORY resources:
 * 1. Ensures repo is cloned (or pulls latest)
 * 2. Discovers new commits on default branch
 * 3. Saves NEW commits to MongoDB for GitContinuousIndexer to process
 */
@Component
class GitPollingHandler(
    private val gitRepositoryService: GitRepositoryService,
) {

    fun canHandle(connectionDocument: ConnectionDocument): Boolean =
        connectionDocument.availableCapabilities.contains(ConnectionCapability.REPOSITORY)

    suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        var itemsDiscovered = 0
        var itemsCreated = 0
        var errors = 0

        logger.info { "Polling Git repositories for connection: ${connectionDocument.name}" }

        // Process each project that uses this connection for REPOSITORY
        for (project in context.projects) {
            val repoResources = project.resources.filter {
                it.connectionId == connectionDocument.id.value &&
                    it.capability == ConnectionCapability.REPOSITORY
            }

            for (resource in repoResources) {
                try {
                    logger.debug { "Syncing repo ${resource.resourceIdentifier} for project ${project.name}" }
                    gitRepositoryService.syncRepository(project, resource)
                    itemsDiscovered++
                    itemsCreated++ // commits are saved by syncRepository
                } catch (e: Exception) {
                    logger.error(e) { "Failed to sync repo ${resource.resourceIdentifier} for project ${project.name}" }
                    errors++
                }
            }
        }

        return PollingResult(
            itemsDiscovered = itemsDiscovered,
            itemsCreated = itemsCreated,
            errors = errors,
        )
    }
}
