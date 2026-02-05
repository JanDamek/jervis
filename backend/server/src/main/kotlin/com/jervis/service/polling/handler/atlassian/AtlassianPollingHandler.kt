package com.jervis.service.polling.handler.atlassian

import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.integration.bugtracker.internal.polling.BugTrackerPollingHandler
import com.jervis.integration.wiki.internal.polling.WikiPollingHandler
import com.jervis.service.polling.PollingResult
import com.jervis.service.polling.handler.PollingContext
import com.jervis.service.polling.handler.PollingHandler
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class AtlassianPollingHandler(
    private val bugTrackerPollingHandler: BugTrackerPollingHandler,
    private val wikiPollingHandler: WikiPollingHandler,
) : PollingHandler {
    private val logger = KotlinLogging.logger {}

    override val provider: ProviderEnum = ProviderEnum.ATLASSIAN

    override fun canHandle(connectionDocument: ConnectionDocument): Boolean {
        return connectionDocument.provider == ProviderEnum.ATLASSIAN
    }

    override suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        var totalDiscovered = 0
        var totalCreated = 0
        var totalSkipped = 0
        var totalErrors = 0

        // Check capabilities and delegate
        if (connectionDocument.availableCapabilities.contains(ConnectionCapability.BUGTRACKER)) {
            try {
                val result = bugTrackerPollingHandler.poll(connectionDocument, context)
                totalDiscovered += result.itemsDiscovered
                totalCreated += result.itemsCreated
                totalSkipped += result.itemsSkipped
                totalErrors += result.errors
            } catch (e: Exception) {
                logger.error(e) { "Error polling Atlassian BugTracker for ${connectionDocument.name}" }
                totalErrors++
            }
        }

        if (connectionDocument.availableCapabilities.contains(ConnectionCapability.WIKI)) {
            try {
                val result = wikiPollingHandler.poll(connectionDocument, context)
                totalDiscovered += result.itemsDiscovered
                totalCreated += result.itemsCreated
                totalSkipped += result.itemsSkipped
                totalErrors += result.errors
            } catch (e: Exception) {
                logger.error(e) { "Error polling Atlassian Wiki for ${connectionDocument.name}" }
                totalErrors++
            }
        }

        return PollingResult(
            itemsDiscovered = totalDiscovered,
            itemsCreated = totalCreated,
            itemsSkipped = totalSkipped,
            errors = totalErrors,
        )
    }
}
