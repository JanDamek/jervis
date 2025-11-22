package com.jervis.service.email

import com.jervis.domain.email.CreateEmailAccountRequest
import com.jervis.domain.email.EmailAccount
import com.jervis.domain.email.EmailValidationResult
import com.jervis.domain.email.UpdateEmailAccountRequest
import com.jervis.entity.EmailAccountDocument
import com.jervis.mapper.toDomain
import com.jervis.repository.EmailAccountMongoRepository
import jakarta.mail.Session
import jakarta.mail.Store
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.Properties

private val logger = KotlinLogging.logger {}

@Service
class EmailAccountService(
    private val emailAccountRepository: EmailAccountMongoRepository,
) {
    suspend fun createEmailAccount(request: CreateEmailAccountRequest): EmailAccount {
        logger.info { "Creating email account for client ${request.clientId}, provider ${request.provider}" }

        val document =
            EmailAccountDocument(
                clientId = request.clientId,
                projectId = request.projectId,
                provider = request.provider,
                displayName = request.displayName,
                description = request.description,
                email = request.email,
                username = request.username,
                password = request.password,
                serverHost = request.serverHost,
                serverPort = request.serverPort,
                useSsl = request.useSsl,
                isActive = true,
            )

        val saved =
            emailAccountRepository.save(document)
                ?: throw IllegalStateException("Failed to save email account")

        logger.info { "Email account created: ${saved.id}" }

        return saved.toDomain()
    }

    suspend fun updateEmailAccount(request: UpdateEmailAccountRequest): EmailAccount {
        logger.info { "Updating email account ${request.accountId}" }

        val existing =
            emailAccountRepository.findById(request.accountId)
                ?: throw IllegalArgumentException("Email account not found: ${request.accountId}")

        val updated =
            existing.copy(
                provider = request.provider,
                displayName = request.displayName,
                description = request.description,
                email = request.email,
                username = request.username,
                password = request.password ?: existing.password,
                serverHost = request.serverHost,
                serverPort = request.serverPort,
                useSsl = request.useSsl,
            )

        val saved = emailAccountRepository.save(updated)

        logger.info { "Email account updated: ${saved.id}" }

        return saved.toDomain()
    }

    suspend fun getEmailAccount(accountId: ObjectId): EmailAccount? {
        val document = emailAccountRepository.findById(accountId)
        return document?.toDomain()
    }

    fun listEmailAccounts(
        clientId: ObjectId?,
        projectId: ObjectId?,
    ): Flow<EmailAccount> {
        val accounts =
            when {
                projectId != null -> emailAccountRepository.findByProjectId(projectId)
                clientId != null -> emailAccountRepository.findByClientId(clientId)
                else -> emailAccountRepository.findAll()
            }

        return accounts.map { it.toDomain() }
    }

    suspend fun deleteEmailAccount(accountId: ObjectId) {
        logger.info { "Deleting email account $accountId" }
        emailAccountRepository.deleteById(accountId)
        logger.info { "Email account deleted: $accountId" }
    }

    suspend fun validateEmailAccount(accountId: ObjectId): EmailValidationResult {
        val account =
            emailAccountRepository.findById(accountId)
                ?: return EmailValidationResult(isValid = false, message = "Account not found")

        return validateImapConnection(account.toDomain())
    }

    private suspend fun validateImapConnection(account: EmailAccount): EmailValidationResult {
        if (!account.hasValidImapConfig()) {
            return EmailValidationResult(
                isValid = false,
                message = "Invalid IMAP configuration: missing server host, port, or password",
            )
        }

        val username = account.getImapUsername()

        return try {
            val props =
                Properties().apply {
                    put("mail.store.protocol", "imap")
                    put("mail.imap.host", account.serverHost)
                    put("mail.imap.port", account.serverPort.toString())
                    put("mail.imap.ssl.enable", account.useSsl.toString())
                    put("mail.imap.starttls.enable", "true")
                    put("mail.imap.connectiontimeout", "10000")
                    put("mail.imap.timeout", "10000")
                }

            val session = Session.getInstance(props)
            val store: Store = session.getStore("imap")

            store.connect(account.serverHost, username, account.password)
            store.close()

            logger.info { "IMAP connection validated for account ${account.id}" }
            EmailValidationResult(isValid = true, message = "Connection successful")
        } catch (e: Exception) {
            logger.warn(e) { "IMAP validation failed for account ${account.id}" }
            EmailValidationResult(isValid = false, message = e.message)
        }
    }
}
