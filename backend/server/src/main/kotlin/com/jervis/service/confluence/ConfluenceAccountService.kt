package com.jervis.service.confluence

import com.jervis.entity.ConfluenceAccountDocument
import com.jervis.repository.mongo.ConfluenceAccountMongoRepository
import com.jervis.service.task.UserTaskService
import kotlinx.coroutines.flow.firstOrNull
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service for managing Confluence Atlassian account authentication status.
 *
 * Rules:
 * - Accounts can be used for API/indexing ONLY when authStatus == VALID.
 * - UI can set VALID state exclusively via an explicit connection test endpoint.
 * - On any authentication error (401/403), mark account as INVALID and create a UserTask
 *   instructing the user to fix settings and re-test the connection.
 */
@Service
class ConfluenceAccountService(
    private val accountRepository: ConfluenceAccountMongoRepository,
    private val userTaskService: UserTaskService,
    private val confluenceApiClient: ConfluenceApiClient,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun markAuthInvalid(
        account: ConfluenceAccountDocument,
        errorMessage: String?,
    ): ConfluenceAccountDocument {
        val updated =
            account.copy(
                authStatus = "INVALID",
                lastAuthCheckedAt = Instant.now(),
                lastErrorMessage = errorMessage ?: account.lastErrorMessage,
                updatedAt = Instant.now(),
            )
        val saved = accountRepository.save(updated)

        runCatching {
            val title = "Confluence account authentication failed: ${account.siteName}"
            val description =
                buildString {
                    appendLine("The Confluence account cannot authenticate (401/403). It has been disabled for API calls and indexing.")
                    appendLine()
                    appendLine("Account ID: ${account.id}")
                    appendLine("Site: ${account.siteUrl}")
                    appendLine("Cloud ID: ${account.cloudId}")
                    errorMessage?.let {
                        appendLine()
                        appendLine("Error: $it")
                    }
                    appendLine()
                    appendLine("Action required:")
                    appendLine("1) Open Confluence account settings in Jervis")
                    appendLine("2) Update the Atlassian token/credentials")
                    appendLine("3) Click 'Test Connection' to re-enable the account (authStatus → VALID)")
                }
            userTaskService.createTask(
                title = title,
                description = description,
                projectId = account.projectId,
                clientId = account.clientId,
                sourceType = com.jervis.domain.task.TaskSourceType.EXTERNAL_SYSTEM,
                sourceUri = "confluence-account-auth://${account.id}",
                metadata = mapOf("accountId" to account.id.toHexString(), "siteUrl" to account.siteUrl),
            )
        }.onFailure { e ->
            logger.warn(e) { "Failed to create user task for auth failure of Confluence account ${account.id}" }
        }

        logger.info { "Confluence account ${account.id} marked as INVALID due to authentication error" }
        return saved
    }

    suspend fun markAuthValid(accountId: ObjectId): ConfluenceAccountDocument {
        val existing =
            accountRepository.findById(accountId)
                ?: error("Confluence account not found: $accountId")
        val updated =
            existing.copy(
                authStatus = "VALID",
                lastAuthCheckedAt = Instant.now(),
                lastErrorMessage = null,
                updatedAt = Instant.now(),
            )
        val saved = accountRepository.save(updated)
        logger.info { "Confluence account $accountId marked as VALID" }
        return saved
    }

    /**
      * UI-triggered connection test. Only this method is allowed to set authStatus to VALID.
      * On success: sets VALID. On failure: sets INVALID and creates a user task.
      */
    suspend fun testConnection(accountId: ObjectId): ConfluenceAccountDocument {
        val account =
            accountRepository.findById(accountId)
                ?: error("Confluence account not found: $accountId")

        return try {
            // Lightweight check: list spaces (first page), ensures token validity
            val flow = confluenceApiClient.listSpaces(account)
            // Touch first or just collect none; simply executing a single page request is enough
            flow.firstOrNull()
            markAuthValid(accountId)
        } catch (e: Exception) {
            logger.warn(e) { "Confluence test connection failed for account $accountId" }
            markAuthInvalid(account, e.message)
        }
    }
}
