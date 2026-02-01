package com.jervis.service.oauth2

import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.types.ConnectionId
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * OAuth2 Service for handling GitHub, GitLab, and Bitbucket OAuth2 flows.
 *
 * Uses global OAuth2 credentials from application.yml (jervis.oauth2.*).
 * Client ID and Secret are configured once for the entire Jervis application.
 *
 * Flow:
 * 1. UI calls getAuthorizationUrl(connectionId)
 * 2. UI opens browser with authorization URL
 * 3. User authorizes on provider
 * 4. Provider redirects to /oauth2/callback with code
 * 5. handleCallback() exchanges code for token
 * 6. Token is stored in connection
 */
@Service
class OAuth2Service(
    private val connectionService: com.jervis.service.connection.ConnectionService,
    private val httpClient: HttpClient,
    private val oauth2Properties: com.jervis.configuration.properties.OAuth2Properties
) {
    private val log = KotlinLogging.logger {}

    // Temporary storage for OAuth2 state (in production, use Redis or similar)
    private val pendingAuthorizations = mutableMapOf<String, ConnectionId>() // state -> connectionId

    /**
     * Generate authorization URL for OAuth2 flow.
     * Uses global OAuth2 credentials from application.yml.
     */
    suspend fun getAuthorizationUrl(connectionId: ConnectionId): OAuth2AuthorizationResponse {
        log.info { "Initiating OAuth2 flow for connectionId=$connectionId" }

        val connection = connectionService.findById(connectionId)
            ?: throw IllegalArgumentException("Connection not found: $connectionId")

        val provider = determineProvider(connection)
        val providerConfig = getProviderConfig(provider)

        if (!providerConfig.isConfigured()) {
            throw IllegalArgumentException("OAuth2 not configured for provider: $provider. Set GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET environment variables.")
        }

        val state = UUID.randomUUID().toString()
        pendingAuthorizations[state] = connectionId

        val authorizationUrl = buildAuthorizationUrl(
            provider = provider,
            clientId = providerConfig.clientId,
            redirectUri = oauth2Properties.redirectUri,
            state = state
        )

        return OAuth2AuthorizationResponse(
            authorizationUrl = authorizationUrl,
            state = state
        )
    }

    private fun determineProvider(connection: ConnectionDocument): String {
        // Check if it's a git provider
        connection.gitProvider?.let {
            return it.name.lowercase()
        }

        // Check if it's Atlassian based on baseUrl or capabilities
        if (connection.baseUrl.contains("atlassian.net") ||
            (connection.availableCapabilities.contains(ConnectionDocument.ConnectionCapability.BUGTRACKER) &&
             connection.availableCapabilities.contains(ConnectionDocument.ConnectionCapability.WIKI))) {
            return "atlassian"
        }

        // Default to github
        return "github"
    }

    private fun getProviderConfig(provider: String): com.jervis.configuration.properties.OAuth2ProviderConfig {
        return when (provider) {
            "github" -> oauth2Properties.github
            "gitlab" -> oauth2Properties.gitlab
            "bitbucket" -> oauth2Properties.bitbucket
            "atlassian" -> oauth2Properties.atlassian
            else -> throw IllegalArgumentException("Unsupported OAuth2 provider: $provider")
        }
    }

    /**
     * Handle OAuth2 callback and exchange code for access token.
     * Uses global OAuth2 credentials from application.yml.
     */
    suspend fun handleCallback(code: String, state: String): OAuth2CallbackResult {
        val connectionId = pendingAuthorizations.remove(state)
            ?: return OAuth2CallbackResult.InvalidState

        return try {
            val connection = connectionService.findById(connectionId)
                ?: return OAuth2CallbackResult.Error("Connection not found")

            val provider = determineProvider(connection)
            val providerConfig = getProviderConfig(provider)

            // Exchange code for access token
            val tokenResponse = exchangeCodeForToken(
                provider = provider,
                code = code,
                clientId = providerConfig.clientId,
                clientSecret = providerConfig.clientSecret,
                redirectUri = oauth2Properties.redirectUri
            )

            // Update connection with access token
            val accessToken = tokenResponse["access_token"] as String
            val updatedConnection = connection.copy(
                credentials = ConnectionDocument.HttpCredentials.Bearer(accessToken),
                state = ConnectionStateEnum.VALID
            )
            connectionService.save(updatedConnection)

            log.info { "Successfully authorized connection: $connectionId" }
            OAuth2CallbackResult.Success

        } catch (e: Exception) {
            log.error(e) { "Failed to complete OAuth2 flow" }
            OAuth2CallbackResult.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun buildAuthorizationUrl(
        provider: String,
        clientId: String,
        redirectUri: String,
        state: String
    ): String {
        val baseUrl = when (provider) {
            "github" -> "https://github.com/login/oauth/authorize"
            "gitlab" -> "https://gitlab.com/oauth/authorize"
            "bitbucket" -> "https://bitbucket.org/site/oauth2/authorize"
            "atlassian" -> "https://auth.atlassian.com/authorize"
            else -> throw IllegalArgumentException("Unsupported OAuth2 provider: $provider")
        }

        // Fetch scopes from the provider service
        val scope = fetchScopesFromService(provider)

        // Atlassian requires additional parameters
        return if (provider == "atlassian") {
            "$baseUrl?audience=api.atlassian.com&client_id=$clientId&redirect_uri=${redirectUri.encodeURLParameter()}&state=$state&scope=${scope.encodeURLParameter()}&response_type=code&prompt=consent"
        } else {
            "$baseUrl?client_id=$clientId&redirect_uri=${redirectUri.encodeURLParameter()}&state=$state&scope=${scope.encodeURLParameter()}&response_type=code"
        }
    }

    private suspend fun fetchScopesFromService(provider: String): String {
        return try {
            val serviceUrl = when (provider) {
                "github" -> "http://jervis-service-github:8085/oauth2/scopes"
                "gitlab" -> "http://jervis-service-gitlab:8086/oauth2/scopes"
                "atlassian" -> "http://jervis-atlassian:8084/oauth2/scopes"
                "bitbucket" -> {
                    // Bitbucket doesn't have a dedicated service yet, use default scopes
                    return "account team repository webhook pullrequest:write issue:write wiki snippet project"
                }
                else -> throw IllegalArgumentException("Unsupported OAuth2 provider: $provider")
            }

            val response = httpClient.get(serviceUrl)
            val responseText = response.bodyAsText()

            // Parse JSON response {"scopes":"..."}
            val scopesMatch = Regex(""""scopes"\s*:\s*"([^"]+)"""").find(responseText)
            scopesMatch?.groupValues?.get(1) ?: throw IllegalStateException("Failed to parse scopes from service response")
        } catch (e: Exception) {
            log.error(e) { "Failed to fetch scopes from $provider service, using fallback" }
            // Fallback scopes
            when (provider) {
                "github" -> "repo user admin:org admin:public_key admin:repo_hook admin:org_hook gist notifications workflow write:discussion write:packages delete:packages admin:gpg_key admin:ssh_signing_key codespace project security_events"
                "gitlab" -> "api read_user read_api read_repository write_repository read_registry write_registry sudo admin_mode"
                "atlassian" -> "read:jira-user read:jira-work write:jira-work read:confluence-content.all read:confluence-content.summary read:confluence-content.permission read:confluence-props read:confluence-space.summary read:confluence-groups read:confluence-user write:confluence-content write:confluence-space offline_access"
                "bitbucket" -> "account team repository webhook pullrequest:write issue:write wiki snippet project"
                else -> throw IllegalArgumentException("Unsupported OAuth2 provider: $provider")
            }
        }
    }

    private suspend fun exchangeCodeForToken(
        provider: String,
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): Map<String, String> {
        val tokenUrl = when (provider) {
            "github" -> "https://github.com/login/oauth/access_token"
            "gitlab" -> "https://gitlab.com/oauth/token"
            "bitbucket" -> "https://bitbucket.org/site/oauth2/access_token"
            "atlassian" -> "https://auth.atlassian.com/oauth/token"
            else -> throw IllegalArgumentException("Unsupported OAuth2 provider: $provider")
        }

        val response = if (provider == "github") {
            // GitHub expects form-urlencoded
            httpClient.post(tokenUrl) {
                contentType(ContentType.Application.FormUrlEncoded)
                accept(ContentType.Application.Json)
                setBody("client_id=$clientId&client_secret=$clientSecret&code=$code&redirect_uri=$redirectUri")
            }
        } else {
            // GitLab, Bitbucket, and Atlassian expect form parameters
            httpClient.submitForm(
                url = tokenUrl,
                formParameters = parameters {
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    append("code", code)
                    append("redirect_uri", redirectUri)
                    append("grant_type", "authorization_code")
                }
            ) {
                accept(ContentType.Application.Json)
            }
        }

        val responseText = response.bodyAsText()
        log.info { "OAuth2 token response: $responseText" }

        // Parse response - could be JSON or URL-encoded
        return if (responseText.startsWith("{")) {
            // JSON response
            parseJsonResponse(responseText)
        } else {
            // URL-encoded response (GitHub)
            parseUrlEncodedResponse(responseText)
        }
    }

    private fun parseJsonResponse(json: String): Map<String, String> {
        // Simple JSON parsing for access_token
        val result = mutableMapOf<String, String>()
        val accessTokenMatch = Regex(""""access_token"\s*:\s*"([^"]+)"""").find(json)
        if (accessTokenMatch != null) {
            result["access_token"] = accessTokenMatch.groupValues[1]
        }
        return result
    }

    private fun parseUrlEncodedResponse(text: String): Map<String, String> {
        return text.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
    }
}

data class OAuth2AuthorizationResponse(
    val authorizationUrl: String,
    val state: String
)

sealed class OAuth2CallbackResult {
    object Success : OAuth2CallbackResult()
    object InvalidState : OAuth2CallbackResult()
    data class Error(val message: String) : OAuth2CallbackResult()
}
