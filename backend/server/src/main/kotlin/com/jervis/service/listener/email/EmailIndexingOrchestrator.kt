package com.jervis.service.listener.email

import com.jervis.entity.EmailAccountDocument
import com.jervis.service.listener.email.imap.ImapClient
import com.jervis.service.listener.email.state.EmailMessageStateManager
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Simplified orchestrator - only syncs message headers from IMAP to DB.
 * Indexing is handled by EmailContinuousIndexer running separately.
 */
@Service
class EmailIndexingOrchestrator(
    private val imapClient: ImapClient,
    private val stateManager: EmailMessageStateManager,
) {
    /**
     * Syncs message IDs/headers from IMAP and saves as NEW in DB.
     * Does NOT process/index messages - that's done by EmailContinuousIndexer.
     */
    suspend fun syncMessageHeaders(account: EmailAccountDocument) {
        logger.info { "Syncing message headers for account ${account.id} (${account.email})" }

        // Fail fast: let exceptions propagate to scheduler so timestamp isn't updated on failure.
        val messageIdsFlow = imapClient.fetchMessageIds(account)
        stateManager.saveNewMessageIds(account.id, messageIdsFlow)
        logger.info { "Successfully synced headers for account ${account.id}" }
    }
}
