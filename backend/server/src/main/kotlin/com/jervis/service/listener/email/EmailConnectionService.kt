package com.jervis.service.listener.email

import com.jervis.entity.EmailAccountDocument
import com.jervis.repository.EmailAccountMongoRepository
import com.jervis.service.task.TaskSourceType
import com.jervis.service.task.UserTaskService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Service managing email account authentication status.
 *
 * Rules:
 * - Email accounts are used for IMAP/indexing ONLY when authStatus == VALID.
 * - UI can set VALID via an explicit connection test endpoint.
 * - On any authentication error, mark account as INVALID and create a UserTask
 *   instructing the user to fix settings and re-test the connection.
 */
@Service
class EmailConnectionService(
    private val emailAccountRepository: EmailAccountMongoRepository,
    private val userTaskService: UserTaskService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun markAuthInvalid(
        account: EmailAccountDocument,
        errorMessage: String?,
    ): EmailAccountDocument {
        val updated =
            account.copy(
                authStatus = "INVALID",
                lastAuthCheckedAt = Instant.now(),
                lastErrorMessage = errorMessage ?: account.lastErrorMessage,
                isActive = false, // Stop indexing attempts
            )
        val saved = emailAccountRepository.save(updated)

        runCatching {
            val title = "Email account authentication failed: ${account.email}"
            val description =
                buildString {
                    appendLine("The email account cannot authenticate. It has been disabled for IMAP access and indexing.")
                    appendLine()
                    appendLine("Account ID: ${account.id}")
                    appendLine("Email: ${account.email}")
                    account.serverHost?.let { appendLine("Server: $it:${account.serverPort}") }
                    errorMessage?.let {
                        appendLine()
                        appendLine("Error: $it")
                    }
                    appendLine()
                    appendLine("Action required:")
                    appendLine("1) Open email account settings in Jervis")
                    appendLine("2) Update the password/credentials")
                    appendLine("3) Click 'Test Connection' to re-enable the account (authStatus → VALID)")
                }
            userTaskService.createTask(
                title = title,
                description = description,
                projectId = account.projectId,
                clientId = account.clientId,
                sourceType = TaskSourceType.EMAIL,
                sourceUri = "email-connection-auth://${account.id}",
                metadata = mapOf("clientId" to account.clientId.toHexString(), "email" to account.email),
            )
        }.onFailure { e ->
            logger.warn(e) { "Failed to create user task for auth failure of email account ${account.id}" }
        }

        logger.info { "Email account ${account.id} (${account.email}) marked as INVALID due to authentication error" }
        return saved
    }

    suspend fun markAuthValid(accountId: ObjectId): EmailAccountDocument {
        val existing = emailAccountRepository.findById(accountId) ?: error("Email account not found: $accountId")
        val updated =
            existing.copy(
                authStatus = "VALID",
                lastAuthCheckedAt = Instant.now(),
                lastErrorMessage = null,
                isActive = true, // Re-enable indexing
            )
        val saved = emailAccountRepository.save(updated)
        logger.info { "Email account ${accountId.toHexString()} (${existing.email}) marked as VALID" }
        return saved
    }

    /**
     * UI-triggered connection test. Only this method is allowed to set authStatus to VALID.
     * On success: sets VALID. On failure: sets INVALID and creates a user task.
     */
    suspend fun testConnection(accountId: ObjectId): EmailAccountDocument {
        val doc = emailAccountRepository.findById(accountId) ?: error("Email account not found: $accountId")

        return try {
            // TODO: Add actual IMAP connection test here
            // For now, just mark as valid - implement actual test in ImapClient
            markAuthValid(accountId)
        } catch (e: Exception) {
            markAuthInvalid(doc, e.message)
        }
    }
}
