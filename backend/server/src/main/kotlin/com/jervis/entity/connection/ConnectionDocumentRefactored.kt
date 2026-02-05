package com.jervis.entity.connection

import com.jervis.common.types.ConnectionId
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ProviderEnum
import kotlinx.serialization.Serializable
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Simplified connection document that stores only essential configuration for connecting to external providers.
 *
 * Architecture principles:
 * - Provider-specific logic lives in microservices (service-github, service-gitlab, service-atlassian, etc.)
 * - This document stores ONLY what's needed to establish connection
 * - Capabilities are auto-detected based on provider type
 * - Connection configuration is generic and extensible via configMap
 */
@Document(collection = "connections")
@CompoundIndexes(
    CompoundIndex(name = "name_unique_idx", def = "{'name': 1}", unique = true),
    CompoundIndex(name = "provider_state_idx", def = "{'provider': 1, 'state': 1}"),
)
data class ConnectionDocumentNew(
    @Id
    val id: ConnectionId = ConnectionId.generate(),
    /** Human-readable connection name */
    val name: String,
    /** Provider type - determines which microservice handles this connection */
    val provider: ProviderEnum,
    /** Connection state (NEW, VALID, INVALID) */
    var state: ConnectionStateEnum = ConnectionStateEnum.NEW,
    /** Provider-specific configuration as key-value map */
    val config: Map<String, String> = emptyMap(),
    /** Auto-detected capabilities based on provider */
    val availableCapabilities: Set<ConnectionCapability> = emptySet(),
    /** Rate limiting configuration */
    val rateLimitConfig: RateLimitConfig =
        RateLimitConfig(
            maxRequestsPerSecond = 10,
            maxRequestsPerMinute = 100,
        ),
) {
    /**
     * Rate limit configuration applied per provider.
     */
    @Serializable
    data class RateLimitConfig(
        val maxRequestsPerSecond: Int,
        val maxRequestsPerMinute: Int,
    )

    companion object {
        // Common config keys used across providers
        object ConfigKeys {
            // HTTP-based providers (GitHub, GitLab, Atlassian)
            const val BASE_URL = "baseUrl"
            const val AUTH_TYPE = "authType" // "NONE", "BASIC", "BEARER", "OAUTH2"
            const val USERNAME = "username"
            const val PASSWORD = "password"
            const val TOKEN = "token"
            const val TIMEOUT_MS = "timeoutMs"

            // Email providers (IMAP, POP3, SMTP)
            const val HOST = "host"
            const val PORT = "port"
            const val USE_SSL = "useSsl"
            const val USE_TLS = "useTls"
            const val FOLDER = "folder" // for IMAP/POP3

            // OAuth2 providers
            const val CLIENT_ID = "clientId"
            const val CLIENT_SECRET = "clientSecret"
            const val AUTHORIZATION_URL = "authorizationUrl"
            const val TOKEN_URL = "tokenUrl"
            const val REDIRECT_URI = "redirectUri"
            const val SCOPES = "scopes" // comma-separated

            // Provider-specific (stored but not validated by server)
            const val JIRA_PROJECT_KEY = "jiraProjectKey"
            const val CONFLUENCE_SPACE_KEY = "confluenceSpaceKey"
            const val CONFLUENCE_ROOT_PAGE_ID = "confluenceRootPageId"
            const val BITBUCKET_REPO_SLUG = "bitbucketRepoSlug"
            const val GIT_REMOTE_URL = "gitRemoteUrl"
        }
    }
}

/**
 * Helper extensions for typed config access
 */
fun ConnectionDocumentNew.getConfigString(key: String): String? = config[key]

fun ConnectionDocumentNew.getConfigInt(key: String): Int? = config[key]?.toIntOrNull()

fun ConnectionDocumentNew.getConfigBoolean(key: String): Boolean? = config[key]?.toBooleanStrictOrNull()

fun ConnectionDocumentNew.getConfigList(
    key: String,
    separator: String = ",",
): List<String> = config[key]?.split(separator)?.map { it.trim() } ?: emptyList()

/**
 * Capability detection based on provider type
 */
fun ProviderEnum.detectCapabilities(): Set<ConnectionCapability> =
    when (this) {
        ProviderEnum.GITHUB -> {
            setOf(
                ConnectionCapability.REPOSITORY,
                ConnectionCapability.BUGTRACKER,
                ConnectionCapability.WIKI,
                ConnectionCapability.GIT,
            )
        }

        ProviderEnum.GITLAB -> {
            setOf(
                ConnectionCapability.REPOSITORY,
                ConnectionCapability.BUGTRACKER,
                ConnectionCapability.WIKI,
                ConnectionCapability.GIT,
            )
        }

        ProviderEnum.ATLASSIAN -> {
            setOf(
                ConnectionCapability.BUGTRACKER, // Jira
                ConnectionCapability.WIKI, // Confluence
                ConnectionCapability.REPOSITORY, // Bitbucket
            )
        }

        ProviderEnum.IMAP -> {
            setOf(ConnectionCapability.EMAIL_READ, ConnectionCapability.EMAIL_SEND)
        }

        ProviderEnum.SMTP -> {
            setOf(ConnectionCapability.EMAIL_SEND)
        }

        ProviderEnum.POP3 -> {
            setOf(ConnectionCapability.EMAIL_READ)
        }

        ProviderEnum.OAUTH2 -> {
            emptySet()
        } // Capabilities determined after OAuth2 flow completes
    }
