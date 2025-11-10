package com.jervis.service.listener.email.state

import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Resets any email messages stuck in INDEXING state to NEW on application startup.
 * Single-instance server: simple pass over collection is sufficient.
 */
@Component
class EmailStartupRecovery(
    private val stateManager: EmailMessageStateManager,
) {
    private val logger = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    suspend fun onReady() {
        val count = stateManager.resetDanglingIndexingToNewOnStartup()
        if (count > 0) {
            logger.warn { "Email startup recovery reset $count records (INDEXING -> NEW)" }
        }
    }
}
