package com.jervis.configuration.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * OAuth2 configuration properties for GitHub, GitLab, Bitbucket, and Atlassian.
 * These are global application-level OAuth2 credentials.
 */
@Component
@ConfigurationProperties(prefix = "jervis.oauth2")
data class OAuth2Properties(
    var redirectUri: String = "http://localhost:8080/oauth2/callback",
    var github: OAuth2ProviderConfig = OAuth2ProviderConfig(),
    var gitlab: OAuth2ProviderConfig = OAuth2ProviderConfig(),
    var bitbucket: OAuth2ProviderConfig = OAuth2ProviderConfig(),
    var atlassian: OAuth2ProviderConfig = OAuth2ProviderConfig()
)

data class OAuth2ProviderConfig(
    var clientId: String = "",
    var clientSecret: String = "",
    var scopes: String = ""
) {
    fun isConfigured(): Boolean = clientId.isNotBlank() && clientSecret.isNotBlank()

    fun getScopesList(): List<String> = if (scopes.isBlank()) {
        emptyList()
    } else {
        scopes.split(",").map { it.trim() }
    }
}
