package com.jervis.service.email

import com.jervis.domain.email.EmailProviderEnum
import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.email.ValidateResponse
import com.jervis.entity.mongo.EmailAccountDocument
import com.jervis.repository.mongo.EmailAccountMongoRepository
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Session
import jakarta.mail.Store
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Instant
import java.util.Properties

private val logger = KotlinLogging.logger {}

@Service
class EmailAccountService(
    private val emailAccountRepository: EmailAccountMongoRepository,
    builder: WebClient.Builder,
) {
    private val httpClient: WebClient = builder.build()

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

        return when (account.provider) {
            EmailProviderEnum.IMAP, EmailProviderEnum.SEZNAM -> validateImapConnection(account)
            EmailProviderEnum.GMAIL -> validateGmailConnection(account)
            EmailProviderEnum.MICROSOFT -> validateMicrosoftConnection(account)
        }
    }

    suspend fun storeOAuth2Credentials(
        accountId: String,
        accessToken: String,
        refreshToken: String?,
        expiresAt: Instant?,
        scopes: List<String> = emptyList(),
    ) {
        logger.info { "Storing OAuth2 credentials for account $accountId" }

        val account =
            emailAccountRepository.findById(ObjectId(accountId)).awaitFirstOrNull()
                ?: throw IllegalArgumentException("Email account not found: $accountId")

        val updated =
            account.copy(
                accessToken = accessToken,
                refreshToken = refreshToken,
                tokenExpiresAt = expiresAt,
                oauthScopes = scopes,
                updatedAt = Instant.now(),
            )

        emailAccountRepository.save(updated).awaitFirstOrNull()

        logger.info { "OAuth2 credentials stored for account $accountId" }
    }

    private suspend fun validateImapConnection(account: EmailAccountDocument): ValidateResponse {
        if (account.serverHost == null || account.serverPort == null) {
            return ValidateResponse(ok = false, message = "Server host and port are required for IMAP")
        }

        if (account.password == null) {
            return ValidateResponse(ok = false, message = "Password not configured")
        }

        val username = account.username ?: account.email

        return try {
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
            val provider = account.provider
            val message = e.message.orEmpty()
            if (e is AuthenticationFailedException) {
                when (provider) {
                    EmailProviderEnum.GMAIL -> {
                        val hint =
                            if (message.contains("Application-specific password required", ignoreCase = true)) {
                                "Gmail rejected the password: an App Password is required for IMAP. Generate an App Password (https://support.google.com/accounts/answer/185833) or use OAuth by running Test Connection and completing the browser authorization."
                            } else {
                                "Authentication failed with Gmail. If you used your regular account password, IMAP may require an App Password. Alternatively, use OAuth by running Test Connection and completing the browser authorization."
                            }
                        logger.warn { "IMAP authentication failed for Gmail account ${account.id}: $message" }
                        return ValidateResponse(ok = false, message = hint)
                    }

                    EmailProviderEnum.MICROSOFT -> {
                        val hint =
                            "Authentication failed for Microsoft IMAP. Many Microsoft 365 tenants have IMAP Basic Auth disabled. Use OAuth by running Test Connection (you will be redirected to authorize) or enable IMAP OAuth on your tenant."
                        logger.warn { "IMAP authentication failed for Microsoft account ${account.id}: $message" }
                        return ValidateResponse(ok = false, message = hint)
                    }

                    else -> {
                        logger.warn { "IMAP authentication failed for account ${account.id}: $message" }
                        return ValidateResponse(ok = false, message = "Authentication failed: $message")
                    }
                }
            }

            logger.error(e) { "IMAP connection validation failed for account ${account.id}" }
            ValidateResponse(ok = false, message = "Connection failed: $message")
        }
    }

    private suspend fun validateGmailConnection(account: EmailAccountDocument): ValidateResponse {
        // If username/password + server settings are provided, prefer IMAP basic validation
        if (!account.password.isNullOrBlank() && !account.serverHost.isNullOrBlank() && account.serverPort != null) {
            return validateImapConnection(account)
        }

        if (account.accessToken == null) {
            return ValidateResponse(
                ok = false,
                message = "OAuth2 credentials not configured (or provide IMAP password + server settings)",
            )
        }

        return try {
            httpClient
                .get()
                .uri("https://gmail.googleapis.com/gmail/v1/users/me/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}")
                .retrieve()
                .awaitBody<Map<String, Any>>()

            ValidateResponse(ok = true, message = "Gmail OAuth2 connection successful")
        } catch (e: Exception) {
            logger.error(e) { "Gmail OAuth2 validation failed for account ${account.id}" }
            ValidateResponse(ok = false, message = "OAuth2 validation failed: ${e.message}")
        }
    }

    private suspend fun validateMicrosoftConnection(account: EmailAccountDocument): ValidateResponse {
        // If username/password + server settings are provided, allow IMAP validation
        if (!account.password.isNullOrBlank() && !account.serverHost.isNullOrBlank() && account.serverPort != null) {
            return validateImapConnection(account)
        }

        if (account.accessToken == null) {
            return ValidateResponse(
                ok = false,
                message = "OAuth2 credentials not configured (Microsoft generally requires OAuth2; IMAP password may not be supported by your tenant)",
            )
        }

        return try {
            httpClient
                .get()
                .uri("https://graph.microsoft.com/v1.0/me/messages?\$top=1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${account.accessToken}")
                .retrieve()
                .awaitBody<Map<String, Any>>()

            ValidateResponse(ok = true, message = "Microsoft OAuth2 connection successful")
        } catch (e: Exception) {
            logger.error(e) { "Microsoft OAuth2 validation failed for account ${account.id}" }
            ValidateResponse(ok = false, message = "OAuth2 validation failed: ${e.message}")
        }
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
