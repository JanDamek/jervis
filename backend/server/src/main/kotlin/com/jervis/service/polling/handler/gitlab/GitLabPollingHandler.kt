package com.jervis.service.polling.handler.gitlab

import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.handler.PollingContext
import com.jervis.service.polling.handler.PollingHandler
import com.jervis.service.polling.handler.git.GitPollingHandler
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class GitLabPollingHandler(
    private val gitPollingHandler: GitPollingHandler,
) : PollingHandler {
    private val logger = KotlinLogging.logger {}

    override val provider: ProviderEnum = ProviderEnum.GITLAB

    override fun canHandle(connectionDocument: ConnectionDocument): Boolean {
        return connectionDocument.provider == ProviderEnum.GITLAB
    }

    override suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        if (connectionDocument.availableCapabilities.contains(ConnectionCapability.GIT)) {
            try {
                val result = gitPollingHandler.poll(connectionDocument, context)
                totalDiscovered += result.itemsDiscovered
                totalCreated += result.itemsCreated
                totalSkipped += result.itemsSkipped
                totalErrors += result.errors
            } catch (e: Exception) {
                logger.error(e) { "Error polling GitLab Git for ${connectionDocument.name}" }
                totalErrors++
            }
        }

        // TODO: Add BUGTRACKER polling for GitLab Issues

        return PollingResult(
            itemsDiscovered = totalDiscovered,
            itemsCreated = totalCreated,
            itemsSkipped = totalSkipped,
            errors = totalErrors,
        )
    }
}
