package com.jervis.service.email

import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.email.ValidateResponse
import com.jervis.entity.EmailAccountDocument
import com.jervis.repository.mongo.EmailAccountMongoRepository
import jakarta.mail.Session
import jakarta.mail.Store
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Properties

private val logger = KotlinLogging.logger {}

@Service
class EmailAccountService(
    private val emailAccountRepository: EmailAccountMongoRepository,
) {
    suspend fun createEmailAccount(request: CreateOrUpdateEmailAccountRequestDto): EmailAccountDto {
        logger.info { "Creating email account for client ${request.clientId}, provider ${request.provider}" }

        val document =
            EmailAccountDocument(
                clientId = ObjectId(request.clientId),
                projectId = request.projectId?.let { ObjectId(it) },
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
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )

        val saved =
            emailAccountRepository.save(document).awaitFirstOrNull()
                ?: throw IllegalStateException("Failed to save email account")

        logger.info { "Email account created: ${saved.id}" }

        return saved.toDto()
    }

    suspend fun updateEmailAccount(
        accountId: String,
        request: CreateOrUpdateEmailAccountRequestDto,
    ): EmailAccountDto {
        logger.info { "Updating email account $accountId" }

        val existing =
            emailAccountRepository.findById(ObjectId(accountId)).awaitFirstOrNull()
                ?: throw IllegalArgumentException("Email account not found: $accountId")

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
                updatedAt = Instant.now(),
            )

        val saved =
            emailAccountRepository.save(updated).awaitFirstOrNull()
                ?: throw IllegalStateException("Failed to update email account")

        logger.info { "Email account updated: ${saved.id}" }

        return saved.toDto()
    }

    suspend fun getEmailAccount(accountId: String): EmailAccountDto? {
        val document = emailAccountRepository.findById(ObjectId(accountId)).awaitFirstOrNull()
        return document?.toDto()
    }

    suspend fun listEmailAccounts(
        clientId: String?,
        projectId: String?,
    ): List<EmailAccountDto> {
        val accounts =
            when {
                projectId != null -> emailAccountRepository.findByProjectId(ObjectId(projectId)).asFlow().toList()
                clientId != null -> emailAccountRepository.findByClientId(ObjectId(clientId)).asFlow().toList()
                else -> emailAccountRepository.findAll().asFlow().toList()
            }

        return accounts.map { it.toDto() }
    }

    suspend fun deleteEmailAccount(accountId: String) {
        logger.info { "Deleting email account $accountId" }
        emailAccountRepository.deleteById(ObjectId(accountId)).awaitFirstOrNull()
        logger.info { "Email account deleted: $accountId" }
    }

    suspend fun validateEmailAccount(accountId: String): ValidateResponse {
        val account =
            emailAccountRepository.findById(ObjectId(accountId)).awaitFirstOrNull()
                ?: return ValidateResponse(ok = false, message = "Account not found")

        return validateImapConnection(account)
    }

    private suspend fun validateImapConnection(account: EmailAccountDocument): ValidateResponse {
        if (account.serverHost == null || account.serverPort == null) {
            return ValidateResponse(ok = false, message = "Server host and port are required for IMAP")
        }

        if (account.password == null) {
            return ValidateResponse(ok = false, message = "Password not configured")
        }

        val username = account.username ?: account.email

        try {
            val props = Properties()
            props["mail.store.protocol"] = "imap"
            props["mail.imap.host"] = account.serverHost
            props["mail.imap.port"] = account.serverPort.toString()
            props["mail.imap.ssl.enable"] = account.useSsl.toString()
            props["mail.imap.starttls.enable"] = "true"
            props["mail.imap.connectiontimeout"] = "10000"
            props["mail.imap.timeout"] = "10000"

            val session = Session.getInstance(props)
            val store: Store = session.getStore("imap")

            store.connect(account.serverHost, username, account.password)
            store.close()

            logger.info { "IMAP connection validated for account ${account.id}" }
            ValidateResponse(ok = true, message = "Connection successful")
        } catch (e: Exception) {
            return ValidateResponse(ok = false, message = e.message)
        }
        return ValidateResponse(ok = true, message = "Email account validated")
    }

    private fun EmailAccountDocument.toDto(): EmailAccountDto =
        EmailAccountDto(
            id = this.id.toHexString(),
            clientId = this.clientId.toHexString(),
            projectId = this.projectId?.toHexString(),
            provider = this.provider,
            displayName = this.displayName,
            description = this.description,
            email = this.email,
            username = this.username,
            serverHost = this.serverHost,
            serverPort = this.serverPort,
            useSsl = this.useSsl,
            hasPassword = this.password != null,
            hasOAuthToken = this.accessToken != null,
            tokenExpiresAt = this.tokenExpiresAt?.toString(),
            isActive = this.isActive,
            lastPolledAt = this.lastPolledAt?.toString(),
        )
}
