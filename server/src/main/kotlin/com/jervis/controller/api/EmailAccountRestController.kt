package com.jervis.controller.api

import com.jervis.configuration.EmailOAuth2Properties
import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.email.ValidateResponse
import com.jervis.service.email.EmailAccountService
import com.jervis.service.email.OAuth2Service
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/email")
class EmailAccountRestController(
    private val emailAccountService: EmailAccountService,
    private val oAuth2Service: OAuth2Service,
    private val emailOAuth2Properties: EmailOAuth2Properties,
) {
    @PostMapping("/accounts")
    suspend fun createEmailAccount(
        @RequestBody request: CreateOrUpdateEmailAccountRequestDto,
    ): ResponseEntity<EmailAccountDto> {
        logger.info { "Creating email account for client ${request.clientId}" }
        val account = emailAccountService.createEmailAccount(request)

        val validation = emailAccountService.validateEmailAccount(account.id ?: "")
        if (!validation.ok) {
            logger.warn { "Created account ${account.id} failed validation: ${validation.message}" }
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(account)
    }

    @PutMapping("/accounts/{accountId}")
    suspend fun updateEmailAccount(
        @PathVariable accountId: String,
        @RequestBody request: CreateOrUpdateEmailAccountRequestDto,
    ): ResponseEntity<EmailAccountDto> {
        logger.info { "Updating email account $accountId" }
        val account = emailAccountService.updateEmailAccount(accountId, request)

        val validation = emailAccountService.validateEmailAccount(accountId)
        if (!validation.ok) {
            logger.warn { "Updated account $accountId failed validation: ${validation.message}" }
        }

        return ResponseEntity.ok(account)
    }

    @GetMapping("/accounts/{accountId}")
    suspend fun getEmailAccount(
        @PathVariable accountId: String,
    ): ResponseEntity<EmailAccountDto> {
        val account = emailAccountService.getEmailAccount(accountId)
        return if (account != null) {
            ResponseEntity.ok(account)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/accounts")
    suspend fun listEmailAccounts(
        @RequestParam(required = false) clientId: String?,
        @RequestParam(required = false) projectId: String?,
    ): ResponseEntity<List<EmailAccountDto>> {
        val accounts = emailAccountService.listEmailAccounts(clientId, projectId)
        return ResponseEntity.ok(accounts)
    }

    @DeleteMapping("/accounts/{accountId}")
    suspend fun deleteEmailAccount(
        @PathVariable accountId: String,
    ): ResponseEntity<Void> {
        logger.info { "Deleting email account $accountId" }
        emailAccountService.deleteEmailAccount(accountId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/accounts/{accountId}/validate")
    suspend fun validateEmailAccount(
        @PathVariable accountId: String,
    ): ResponseEntity<ValidateResponse> {
        logger.info { "Validating email account $accountId" }
        val result = emailAccountService.validateEmailAccount(accountId)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/oauth/google/authorize")
    suspend fun initiateGoogleOAuth(
        @RequestParam accountId: String,
        request: ServerHttpRequest,
    ): ResponseEntity<Map<String, String>> {
        logger.info { "Initiating Google OAuth for account $accountId" }
        return try {
            val redirectUri = buildCallbackUri(request, GOOGLE_CALLBACK_PATH)
            val authUrl = oAuth2Service.getGoogleAuthorizationUrl(accountId, redirectUri)
            ResponseEntity.ok(mapOf("authorizationUrl" to authUrl))
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "Cannot initiate Google OAuth: ${e.message}" }
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message.orEmpty()))
        } catch (e: IllegalStateException) {
            logger.warn(e) { "Cannot initiate Google OAuth: ${e.message}" }
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message.orEmpty()))
        }
    }

    @GetMapping("/oauth/google/metadata")
    suspend fun getGoogleOAuthMetadata(request: ServerHttpRequest): ResponseEntity<Map<String, Any>> {
        val google = emailOAuth2Properties.google
        val computed = buildCallbackUri(request, GOOGLE_CALLBACK_PATH)
        val body: Map<String, Any> =
            mapOf(
                "clientIdConfigured" to (google.clientId.isNotBlank()),
                "clientSecretConfigured" to (google.clientSecret.isNotBlank()),
                "redirectUriConfigured" to (google.redirectUri?.isNotBlank() == true),
                "configuredRedirectUri" to (google.redirectUri ?: ""),
                "computedRedirectUri" to computed,
                "usesComputedRedirect" to (google.redirectUri.isNullOrBlank()),
                "expectedCallbackPath" to GOOGLE_CALLBACK_PATH,
                "hint" to "clientId is the OAuth 2.0 Client ID from Google Cloud Console, not your email address.",
            )
        return ResponseEntity.ok(body)
    }

    @GetMapping("/oauth/google/callback")
    suspend fun handleGoogleOAuthCallback(
        @RequestParam code: String,
        @RequestParam state: String,
        request: ServerHttpRequest,
    ): ResponseEntity<String> {
        logger.info { "Handling Google OAuth callback for state: $state" }

        return try {
            val redirectUri = buildCallbackUri(request, GOOGLE_CALLBACK_PATH)
            oAuth2Service.handleGoogleCallback(code, state, redirectUri)
            ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create("/oauth/success.html"))
                .body("<html><body><h1>OAuth successful!</h1><p>You can close this window.</p></body></html>")
        } catch (e: Exception) {
            logger.error(e) { "Google OAuth callback failed" }
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("<html><body><h1>OAuth failed</h1><p>${e.message}</p></body></html>")
        }
    }

    @GetMapping("/oauth/microsoft/authorize")
    suspend fun initiateMicrosoftOAuth(
        @RequestParam accountId: String,
        request: ServerHttpRequest,
    ): ResponseEntity<Map<String, String>> {
        logger.info { "Initiating Microsoft OAuth for account $accountId" }
        return try {
            val redirectUri = buildCallbackUri(request, MICROSOFT_CALLBACK_PATH)
            val authUrl = oAuth2Service.getMicrosoftAuthorizationUrl(accountId, redirectUri)
            ResponseEntity.ok(mapOf("authorizationUrl" to authUrl))
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "Cannot initiate Microsoft OAuth: ${e.message}" }
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message.orEmpty()))
        } catch (e: IllegalStateException) {
            logger.warn(e) { "Cannot initiate Microsoft OAuth: ${e.message}" }
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message.orEmpty()))
        }
    }

    @GetMapping("/oauth/microsoft/callback")
    suspend fun handleMicrosoftOAuthCallback(
        @RequestParam code: String,
        @RequestParam state: String,
        request: ServerHttpRequest,
    ): ResponseEntity<String> {
        logger.info { "Handling Microsoft OAuth callback for state: $state" }

        return try {
            val redirectUri = buildCallbackUri(request, MICROSOFT_CALLBACK_PATH)
            oAuth2Service.handleMicrosoftCallback(code, state, redirectUri)
            ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create("/oauth/success.html"))
                .body("<html><body><h1>OAuth successful!</h1><p>You can close this window.</p></body></html>")
        } catch (e: Exception) {
            logger.error(e) { "Microsoft OAuth callback failed" }
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("<html><body><h1>OAuth failed</h1><p>${e.message}</p></body></html>")
        }
    }
}
