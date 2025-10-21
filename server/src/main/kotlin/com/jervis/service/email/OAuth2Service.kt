package com.jervis.service.email

import com.jervis.configuration.EmailOAuth2Properties
import com.jervis.service.websocket.WebSocketChannelType
import com.jervis.service.websocket.WebSocketSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Serializable
data class OAuthCallbackEventDto(
    val accountId: String,
    val provider: String,
    val status: String,
    val email: String? = null,
    val error: String? = null,
)

@Serializable
data class GoogleTokenResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val expires_in: Int,
    val scope: String,
    val token_type: String,
)

@Serializable
data class MicrosoftTokenResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val expires_in: Int,
    val scope: String,
    val token_type: String,
)

@Service
class OAuth2Service(
    private val emailAccountService: EmailAccountService,
    private val webSocketSessionManager: WebSocketSessionManager,
    private val emailOAuth2Properties: EmailOAuth2Properties,
    builder: WebClient.Builder,
) {
    private val httpClient: WebClient = builder.build()
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingStates = ConcurrentHashMap<String, String>()

    fun getGoogleAuthorizationUrl(
        accountId: String,
        redirectUri: String,
    ): String {
        val state = UUID.randomUUID().toString()
        pendingStates[state] = accountId

        val clientId = emailOAuth2Properties.google.clientId
        require(clientId.isNotBlank()) {
            "Google OAuth2 clientId is not configured. Set 'email.oauth.google.client-id' (ENV: EMAIL_OAUTH_GOOGLE_CLIENT_ID). This is NOT your email address — use the OAuth 2.0 Client ID from Google Cloud Console (looks like 123456789012-abcdefg.apps.googleusercontent.com)."
        }
        require(redirectUri.isNotBlank()) {
            "Redirect URI is empty; cannot start OAuth. The server computes it from the current request and it must match the Authorized redirect URI in Google Cloud (typically {baseUrl}/api/v1/email/oauth/google/callback)."
        }

        val scopes =
            listOf(
                "https://www.googleapis.com/auth/gmail.readonly",
                "https://www.googleapis.com/auth/gmail.modify",
            ).joinToString(" ")

        return buildString {
            append("https://accounts.google.com/o/oauth2/v2/auth")
            append("?client_id=").append(clientId)
            append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, "UTF-8"))
            append("&response_type=code")
            append("&scope=").append(java.net.URLEncoder.encode(scopes, "UTF-8"))
            append("&state=").append(state)
            append("&access_type=offline")
            append("&prompt=").append(java.net.URLEncoder.encode("consent select_account", "UTF-8"))
        }
    }

    suspend fun handleGoogleCallback(
        code: String,
        state: String,
        redirectUri: String,
    ) {
        val accountId =
            pendingStates.remove(state)
                ?: throw IllegalArgumentException("Invalid or expired OAuth state")

        logger.info { "Processing Google OAuth callback for account $accountId" }

        try {
            val tokenResponse = exchangeGoogleCodeForTokens(code, redirectUri)
            val expiresAt = Instant.now().plusSeconds(tokenResponse.expires_in.toLong())

            emailAccountService.storeOAuth2Credentials(
                accountId = accountId,
                accessToken = tokenResponse.access_token,
                refreshToken = tokenResponse.refresh_token,
                expiresAt = expiresAt,
                scopes = tokenResponse.scope.split(" "),
            )

            val account = emailAccountService.getEmailAccount(accountId)

            val notification =
                OAuthCallbackEventDto(
                    accountId = accountId,
                    provider = "GOOGLE",
                    status = "SUCCESS",
                    email = account?.email,
                )

            webSocketSessionManager.broadcastToChannel(
                json.encodeToString(notification),
                WebSocketChannelType.NOTIFICATIONS,
            )

            logger.info { "Google OAuth completed successfully for account $accountId" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to process Google OAuth callback for account $accountId" }

            val notification =
                OAuthCallbackEventDto(
                    accountId = accountId,
                    provider = "GOOGLE",
                    status = "FAILURE",
                    error = e.message,
                )

            webSocketSessionManager.broadcastToChannel(
                json.encodeToString(notification),
                WebSocketChannelType.NOTIFICATIONS,
            )

            throw e
        }
    }

    private suspend fun exchangeGoogleCodeForTokens(
        code: String,
        redirectUri: String,
    ): GoogleTokenResponse =
        withContext(Dispatchers.IO) {
            val clientId = emailOAuth2Properties.google.clientId
            val clientSecret = emailOAuth2Properties.google.clientSecret
            require(clientId.isNotBlank()) {
                "Google OAuth2 clientId is not configured. Set 'email.oauth.google.client-id' (ENV: EMAIL_OAUTH_GOOGLE_CLIENT_ID). This is NOT your email address — use the OAuth 2.0 Client ID from Google Cloud Console (looks like 123456789012-abcdefg.apps.googleusercontent.com)."
            }
            require(clientSecret.isNotBlank()) {
                "Google OAuth2 clientSecret is not configured. Set 'email.oauth.google.client-secret' (ENV: EMAIL_OAUTH_GOOGLE_CLIENT_SECRET)."
            }
            require(redirectUri.isNotBlank()) {
                "Redirect URI is empty; cannot exchange code. It must exactly match the Authorized redirect URI in Google Cloud (typically {baseUrl}/api/v1/email/oauth/google/callback)."
            }

            httpClient
                .post()
                .uri("https://oauth2.googleapis.com/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(
                    mapOf(
                        "code" to code,
                        "client_id" to clientId,
                        "client_secret" to clientSecret,
                        "redirect_uri" to redirectUri,
                        "grant_type" to "authorization_code",
                    ).entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" },
                ).awaitExchange { response ->
                    if (response.statusCode() == HttpStatus.OK) {
                        val body = response.awaitBody<String>()
                        json.decodeFromString<GoogleTokenResponse>(body)
                    } else {
                        val errorBody = response.awaitBody<String>()
                        logger.error { "Google token exchange failed: $errorBody" }
                        throw IllegalStateException("Google token exchange failed: ${response.statusCode()}")
                    }
                }
        }

    fun getMicrosoftAuthorizationUrl(
        accountId: String,
        redirectUri: String,
    ): String {
        val state = UUID.randomUUID().toString()
        pendingStates[state] = accountId

        val clientId = emailOAuth2Properties.microsoft.clientId
        require(clientId.isNotBlank()) { "Microsoft OAuth2 clientId is not configured. Set 'email.oauth.microsoft.clientId'." }
        require(redirectUri.isNotBlank()) {
            "Redirect URI is empty; cannot start OAuth. It must exactly match the Redirect URI configured in Azure App Registration (typically {baseUrl}/api/v1/email/oauth/microsoft/callback)."
        }

        val scopes =
            listOf(
                "https://graph.microsoft.com/Mail.Read",
                "https://graph.microsoft.com/Mail.ReadWrite",
                "offline_access",
            ).joinToString(" ")

        return buildString {
            append("https://login.microsoftonline.com/common/oauth2/v2.0/authorize")
            append("?client_id=").append(clientId)
            append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, "UTF-8"))
            append("&response_type=code")
            append("&scope=").append(java.net.URLEncoder.encode(scopes, "UTF-8"))
            append("&state=").append(state)
            append("&prompt=consent")
        }
    }

    suspend fun handleMicrosoftCallback(
        code: String,
        state: String,
        redirectUri: String,
    ) {
        val accountId =
            pendingStates.remove(state)
                ?: throw IllegalArgumentException("Invalid or expired OAuth state")

        logger.info { "Processing Microsoft OAuth callback for account $accountId" }

        try {
            val tokenResponse = exchangeMicrosoftCodeForTokens(code, redirectUri)
            val expiresAt = Instant.now().plusSeconds(tokenResponse.expires_in.toLong())

            emailAccountService.storeOAuth2Credentials(
                accountId = accountId,
                accessToken = tokenResponse.access_token,
                refreshToken = tokenResponse.refresh_token,
                expiresAt = expiresAt,
                scopes = tokenResponse.scope.split(" "),
            )

            val account = emailAccountService.getEmailAccount(accountId)

            val notification =
                OAuthCallbackEventDto(
                    accountId = accountId,
                    provider = "MICROSOFT",
                    status = "SUCCESS",
                    email = account?.email,
                )

            webSocketSessionManager.broadcastToChannel(
                json.encodeToString(notification),
                WebSocketChannelType.NOTIFICATIONS,
            )

            logger.info { "Microsoft OAuth completed successfully for account $accountId" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to process Microsoft OAuth callback for account $accountId" }

            val notification =
                OAuthCallbackEventDto(
                    accountId = accountId,
                    provider = "MICROSOFT",
                    status = "FAILURE",
                    error = e.message,
                )

            webSocketSessionManager.broadcastToChannel(
                json.encodeToString(notification),
                WebSocketChannelType.NOTIFICATIONS,
            )

            throw e
        }
    }

    private suspend fun exchangeMicrosoftCodeForTokens(
        code: String,
        redirectUri: String,
    ): MicrosoftTokenResponse =
        withContext(Dispatchers.IO) {
            val clientId = emailOAuth2Properties.microsoft.clientId
            val clientSecret = emailOAuth2Properties.microsoft.clientSecret
            require(clientId.isNotBlank()) { "Microsoft OAuth2 clientId is not configured. Set 'email.oauth.microsoft.clientId'." }
            require(
                clientSecret.isNotBlank(),
            ) { "Microsoft OAuth2 clientSecret is not configured. Set 'email.oauth.microsoft.clientSecret'." }
            require(redirectUri.isNotBlank()) {
                "Redirect URI is empty; cannot exchange code. It must exactly match the Redirect URI configured in Azure App Registration (typically {baseUrl}/api/v1/email/oauth/microsoft/callback)."
            }

            httpClient
                .post()
                .uri("https://login.microsoftonline.com/common/oauth2/v2.0/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(
                    mapOf(
                        "code" to code,
                        "client_id" to clientId,
                        "client_secret" to clientSecret,
                        "redirect_uri" to redirectUri,
                        "grant_type" to "authorization_code",
                    ).entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" },
                ).awaitExchange { response ->
                    if (response.statusCode() == HttpStatus.OK) {
                        val body = response.awaitBody<String>()
                        json.decodeFromString<MicrosoftTokenResponse>(body)
                    } else {
                        val errorBody = response.awaitBody<String>()
                        logger.error { "Microsoft token exchange failed: $errorBody" }
                        throw IllegalStateException("Microsoft token exchange failed: ${response.statusCode()}")
                    }
                }
        }
}
