package com.jervis.service.oauth2

import com.jervis.domain.OAuth2Provider
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.types.ConnectionId
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import mu.KotlinLogging
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
    private val oauth2Properties: com.jervis.configuration.properties.OAuth2Properties,
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

        val connection =
            connectionService.findById(connectionId)
                ?: throw IllegalArgumentException("Connection not found: $connectionId")

        val provider = determineProvider(connection)
        val providerConfig = getProviderConfig(provider)

        if (!providerConfig.isConfigured()) {
            throw IllegalArgumentException(
                "OAuth2 not configured for provider: $provider. Set GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET environment variables.",
            )
        }

        val state = UUID.randomUUID().toString()
        pendingAuthorizations[state] = connectionId

        val authorizationUrl =
            buildAuthorizationUrl(
                provider = provider,
                clientId = providerConfig.clientId,
                redirectUri = oauth2Properties.redirectUri,
                state = state,
            )

        return OAuth2AuthorizationResponse(
            authorizationUrl = authorizationUrl,
            state = state,
        )
    }

    private fun determineProvider(connection: ConnectionDocument): OAuth2Provider {
        // Check if it's a git provider
        connection.gitProvider?.let {
            return when (it.name) {
                "GITHUB" -> {
                    OAuth2Provider.GITHUB
                }

                "GITLAB" -> {
                    OAuth2Provider.GITLAB
                }

                "BITBUCKET" -> {
                    OAuth2Provider.BITBUCKET
                }

                else -> {
                    // Check if it's Atlassian based on baseUrl or capabilities
                    if (connection.baseUrl.contains("atlassian.net") ||
                        (
                            connection.availableCapabilities.contains(ConnectionCapability.BUGTRACKER) &&
                                connection.availableCapabilities.contains(ConnectionCapability.WIKI)
                        )
                    ) {
                        OAuth2Provider.ATLASSIAN
                    } else {
                        // efault fallback
                        OAuth2Provider.GITHUB
                    }
                }
            }
        }

        // Check if it's Atlassian based on baseUrl or capabilities
        if (connection.baseUrl.contains("atlassian.net") ||
            (
                connection.availableCapabilities.contains(ConnectionCapability.BUGTRACKER) &&
                    connection.availableCapabilities.contains(ConnectionCapability.WIKI)
            )
        ) {
            return OAuth2Provider.ATLASSIAN
        }

        // Default to github
        return OAuth2Provider.GITHUB
    }

    private fun getProviderConfig(provider: OAuth2Provider): com.jervis.configuration.properties.OAuth2ProviderConfig =
        when (provider) {
            OAuth2Provider.GITHUB -> oauth2Properties.github
            OAuth2Provider.GITLAB -> oauth2Properties.gitlab
            OAuth2Provider.BITBUCKET -> oauth2Properties.bitbucket
            OAuth2Provider.ATLASSIAN -> oauth2Properties.atlassian
        }

    /**
     * Handle OAuth2 callback and exchange code for access token.
     * Uses global OAuth2 credentials from application.yml.
     */
    suspend fun handleCallback(
        code: String,
        state: String,
    ): OAuth2CallbackResult {
        val connectionId =
            pendingAuthorizations.remove(state)
                ?: return OAuth2CallbackResult.InvalidState

        return try {
            val connection =
                connectionService.findById(connectionId)
                    ?: return OAuth2CallbackResult.Error("Connection not found")

            val provider = determineProvider(connection)
            val providerConfig = getProviderConfig(provider)

            // Exchange code for access token
            val tokenResponse =
                exchangeCodeForToken(
                    provider = provider,
                    code = code,
                    clientId = providerConfig.clientId,
                    clientSecret = providerConfig.clientSecret,
                    redirectUri = oauth2Properties.redirectUri,
                )

            // Update connection with access token
            val accessToken = tokenResponse["access_token"] as String
            val updatedConnection =
                connection.copy(
                    credentials = ConnectionDocument.HttpCredentials.Bearer(accessToken),
                    state = ConnectionStateEnum.VALID,
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
        provider: OAuth2Provider,
        clientId: String,
        redirectUri: String,
        state: String,
    ): String {
        val baseUrl =
            when (provider) {
                OAuth2Provider.GITHUB -> "https://github.com/login/oauth/authorize"
                OAuth2Provider.GITLAB -> "https://gitlab.com/oauth/authorize"
                OAuth2Provider.BITBUCKET -> "https://bitbucket.org/site/oauth2/authorize"
                OAuth2Provider.ATLASSIAN -> "https://auth.atlassian.com/authorize"
            }

        // Fetch scopes from the provider service
        val scope = fetchScopesFromService(provider)

        // Atlassian requires additional parameters
        return if (provider == OAuth2Provider.ATLASSIAN) {
            "$baseUrl?audience=api.atlassian.com&client_id=$clientId&redirect_uri=${redirectUri.encodeURLParameter()}&state=$state&scope=${scope.encodeURLParameter()}&response_type=code&prompt=consent"
        } else {
            "$baseUrl?client_id=$clientId&redirect_uri=${redirectUri.encodeURLParameter()}&state=$state&scope=${scope.encodeURLParameter()}&response_type=code"
        }
    }

    private suspend fun fetchScopesFromService(provider: OAuth2Provider): String {
        return try {
            val serviceUrl =
                when (provider) {
                    OAuth2Provider.GITHUB -> {
                        "http://jervis-github:8085/oauth2/scopes"
                    }

                    OAuth2Provider.GITLAB -> {
                        "http://jervis-gitlab:8086/oauth2/scopes"
                    }

                    OAuth2Provider.ATLASSIAN -> {
                        "http://jervis-atlassian:8084/oauth2/scopes"
                    }

                    OAuth2Provider.BITBUCKET -> {
                        // Bitbucket doesn't have a dedicated service yet, use default scopes
                        return "account team repository webhook pullrequest:write issue:write wiki snippet project"
                    }
                }

            val response = httpClient.get(serviceUrl)
            val responseText = response.bodyAsText()

            // Parse JSON response {"scopes":"..."}
            val scopesMatch = Regex("""scopes"\s*:\s*"([^"]+)"""").find(responseText)
            scopesMatch?.groupValues?.get(1)
                ?: throw IllegalStateException("Failed to parse scopes from service response")
        } catch (e: Exception) {
            log.error(e) { "Failed to fetch scopes from $provider service, using fallback" }
            // Fallback scopes
            when (provider) {
                OAuth2Provider.GITHUB -> "repo user admin:org admin:public_key admin:repo_hook admin:org_hook gist notifications workflow write:discussion write:packages delete:packages admin:gpg_key admin:ssh_signing_key codespace project security_events"
                OAuth2Provider.GITLAB -> "api read_user read_api read_repository write_repository read_registry write_registry sudo admin_mode"
                OAuth2Provider.ATLASSIAN -> "read:jira-user read:jira-work write:jira-work read:confluence-content.all read:confluence-content.summary read:confluence-content.permission read:confluence-props read:confluence-space.summary read:confluence-groups read:confluence-user write:confluence-content write:confluence-space offline_access"
                OAuth2Provider.BITBUCKET -> "account team repository webhook pullrequest:write issue:write wiki snippet project"
            }
        }
    }

    private suspend fun exchangeCodeForToken(
        provider: OAuth2Provider,
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String,
    ): Map<String, String> {
        val tokenUrl =
            when (provider) {
                OAuth2Provider.GITHUB -> "https://github.com/login/oauth/access_token"
                OAuth2Provider.GITLAB -> "https://gitlab.com/oauth/token"
                OAuth2Provider.BITBUCKET -> "https://bitbucket.org/site/oauth2/access_token"
                OAuth2Provider.ATLASSIAN -> "https://auth.atlassian.com/oauth/token"
            }

        val response =
            if (provider == OAuth2Provider.GITHUB) {
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
                    formParameters =
                        parameters {
                            append("client_id", clientId)
                            append("client_secret", clientSecret)
                            append("code", code)
                            append("redirect_uri", redirectUri)
                            append("grant_type", "authorization_code")
                        },
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
        val accessTokenMatch = Regex("""access_token"\s*:\s*"([^"]+)"""").find(json)
        if (accessTokenMatch != null) {
            result["access_token"] = accessTokenMatch.groupValues[1]
        }
        return result
    }

    private fun parseUrlEncodedResponse(text: String): Map<String, String> =
        text.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
}

data class OAuth2AuthorizationResponse(
    val authorizationUrl: String,
    val state: String,
)

sealed class OAuth2CallbackResult {
    object Success : OAuth2CallbackResult()

    object InvalidState : OAuth2CallbackResult()

    data class Error(
        val message: String,
    ) : OAuth2CallbackResult()
}
