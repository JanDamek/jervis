package com.jervis.entity.connection

import com.jervis.common.types.ConnectionId
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ProviderEnum
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Sealed class hierarchy for all external service connections.
 *
 * Stored directly in MongoDB as a discriminated union.
 * Spring Data MongoDB handles polymorphism automatically.
 *
 * NO domain/entity mappings are needed - entity is used directly in services.
 * Entity stops/starts at the Controller boundary (REST DTOs only there).
 *
 * Supported connection types:
 * - HttpConnectionDocument: REST APIs, Atlassian, Link Scraper
 * - ImapConnectionDocument: Email receiving
 * - Pop3ConnectionDocument: Email receiving (alternative)
 * - SmtpConnectionDocument: Email sending
 * - OAuth2ConnectionDocument: OAuth2 flows
 *
 * Future: SlackConnection, TeamsConnection, DiscordConnection, WebSocketConnection, etc.
 */
@Document(collection = "connections")
@CompoundIndexes(
    CompoundIndex(name = "name_unique_idx", def = "{'name': 1}", unique = true),
    CompoundIndex(name = "state_idx", def = "{'state': 1}"),
)
data class ConnectionDocument(
    @Id
    val id: ConnectionId = ConnectionId.generate(),
    val name: String,
    val provider: ProviderEnum,
    var state: ConnectionStateEnum = ConnectionStateEnum.NEW,
    val rateLimitConfig: RateLimitConfig = RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100),
    val baseUrl: String = "",
    val credentials: HttpCredentials? = null,
    val timeoutMs: Long = 30000,
    val host: String? = null,
    val port: Int = 993,
    val username: String? = null,
    val password: String? = null, // Plain text!
    val useSsl: Boolean = true,
    val folderName: String = "INBOX",
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val clientSecret: String? = null, // Plain text!
    val scopes: List<String> = emptyList(),
    val redirectUri: String? = null,
    val connectionType: ConnectionTypeEnum,
    val useTls: Boolean? = null,
    val gitRemoteUrl: String? = null,
    val gitProvider: com.jervis.domain.git.GitProviderEnum? = null,
    val gitConfig: com.jervis.domain.git.GitConfig? = null,
    // Atlassian multi-purpose connection fields (internal to provider layer if possible, but kept here for schema compatibility)
    val jiraProjectKey: String? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
    val bitbucketRepoSlug: String? = null,
    /**
     * Capabilities this connection provides.
     * A connection can support multiple capabilities:
     * - BUGTRACKER: Issue tracking (Jira, GitHub Issues, GitLab Issues)
     * - WIKI: Documentation/wiki pages (Confluence, GitHub Wiki, GitLab Wiki)
     * - REPOSITORY: Source code repository (GitHub, GitLab, Bitbucket)
     * - EMAIL: Email capabilities (IMAP, SMTP, POP3)
     * - GIT: Git operations
     */
    val availableCapabilities: Set<ConnectionCapability> = emptySet(),
) {
    enum class ConnectionTypeEnum {
        HTTP,
        IMAP,
        POP3,
        SMTP,
        OAUTH2,
        GIT,
    }

    /**
     * Rate limit configuration.
     * Applied per domain/host (not per connection).
     */
    data class RateLimitConfig(
        val maxRequestsPerSecond: Int,
        val maxRequestsPerMinute: Int,
    )

    /**
     * Credentials for HTTP connections (sealed class for type safety).
     */
    sealed class HttpCredentials {
        abstract fun toAuthHeader(): String

        data class Basic(
            val username: String,
            val password: String,
        ) : HttpCredentials() {
            override fun toAuthHeader(): String {
                val credentials = "$username:$password"
                val encoded =
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(credentials.toByteArray())
                return "Basic $encoded"
            }
        }

        data class Bearer(
            val token: String,
        ) : HttpCredentials() {
            override fun toAuthHeader(): String = "Bearer $token"
        }
    }
}

fun ConnectionDocument.HttpCredentials.toAuthType(): com.jervis.dto.connection.HttpAuthTypeEnum =
    when (this) {
        is ConnectionDocument.HttpCredentials.Basic -> com.jervis.dto.connection.HttpAuthTypeEnum.BASIC
        is ConnectionDocument.HttpCredentials.Bearer -> com.jervis.dto.connection.HttpAuthTypeEnum.BEARER
    }

fun ConnectionDocument.HttpCredentials.basicUsername(): String? = (this as? ConnectionDocument.HttpCredentials.Basic)?.username

fun ConnectionDocument.HttpCredentials.basicPassword(): String? = (this as? ConnectionDocument.HttpCredentials.Basic)?.password

fun ConnectionDocument.HttpCredentials.bearerToken(): String? = (this as? ConnectionDocument.HttpCredentials.Bearer)?.token
