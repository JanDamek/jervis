package com.jervis.entity.connection

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.net.URL
import java.time.Instant

/**
 * Sealed class hierarchy for all external service connections.
 *
 * Stored directly in MongoDB as discriminated union.
 * Spring Data MongoDB handles polymorphism automatically.
 *
 * NO domain/entity mapping needed - entity is used directly in services.
 * Entity stops/starts at Controller boundary (REST DTOs only there).
 *
 * Supported connection types:
 * - HttpConnection: REST APIs, Atlassian, Link Scraper
 * - ImapConnection: Email receiving
 * - Pop3Connection: Email receiving (alternative)
 * - SmtpConnection: Email sending
 * - OAuth2Connection: OAuth2 flows
 *
 * Future: SlackConnection, TeamsConnection, DiscordConnection, WebSocketConnection, etc.
 */
@Document(collection = "connections")
@CompoundIndexes(
    CompoundIndex(name = "name_unique_idx", def = "{'name': 1}", unique = true),
    CompoundIndex(name = "enabled_idx", def = "{'enabled': 1}"),
)
sealed class Connection {
    abstract val id: ObjectId
    abstract val name: String
    abstract val enabled: Boolean
    abstract val rateLimitConfig: RateLimitConfig
    abstract val createdAt: Instant
    abstract val updatedAt: Instant
    abstract val createdBy: String?

    /**
     * HTTP/REST API connection.
     * Used for: Atlassian (Jira/Confluence), Link Scraper, any REST API.
     * Credentials stored as PLAIN TEXT (not production app!).
     */
    @TypeAlias("HttpConnection")
    data class HttpConnection(
        @Id override val id: ObjectId = ObjectId.get(),
        override val name: String,
        val baseUrl: String,
        val authType: AuthType = AuthType.NONE,
        val credentials: String? = null, // Plain text: "username:password" or "token"
        val timeoutMs: Long = 30000,
        override val rateLimitConfig: RateLimitConfig = RateLimitConfig(),
        override val enabled: Boolean = true,
        override val createdAt: Instant = Instant.now(),
        override val updatedAt: Instant = Instant.now(),
        override val createdBy: String? = null
    ) : Connection() {
        fun extractDomain(): String {
            return try {
                URL(baseUrl).host
            } catch (e: Exception) {
                baseUrl
            }
        }
    }

    /**
     * IMAP email connection.
     * Used for: Email receiving (Gmail, Outlook, custom IMAP servers).
     * Password stored as PLAIN TEXT (not production app!).
     */
    @TypeAlias("ImapConnection")
    data class ImapConnection(
        @Id override val id: ObjectId = ObjectId.get(),
        override val name: String,
        val host: String,
        val port: Int = 993,
        val username: String,
        val password: String, // Plain text!
        val useSsl: Boolean = true,
        val folderName: String = "INBOX",
        override val rateLimitConfig: RateLimitConfig = RateLimitConfig(),
        override val enabled: Boolean = true,
        override val createdAt: Instant = Instant.now(),
        override val updatedAt: Instant = Instant.now(),
        override val createdBy: String? = null
    ) : Connection()

    /**
     * POP3 email connection.
     * Used for: Email receiving (alternative to IMAP).
     * Password stored as PLAIN TEXT (not production app!).
     */
    @TypeAlias("Pop3Connection")
    data class Pop3Connection(
        @Id override val id: ObjectId = ObjectId.get(),
        override val name: String,
        val host: String,
        val port: Int = 995,
        val username: String,
        val password: String, // Plain text!
        val useSsl: Boolean = true,
        override val rateLimitConfig: RateLimitConfig = RateLimitConfig(),
        override val enabled: Boolean = true,
        override val createdAt: Instant = Instant.now(),
        override val updatedAt: Instant = Instant.now(),
        override val createdBy: String? = null
    ) : Connection()

    /**
     * SMTP email connection.
     * Used for: Email sending.
     * Password stored as PLAIN TEXT (not production app!).
     */
    @TypeAlias("SmtpConnection")
    data class SmtpConnection(
        @Id override val id: ObjectId = ObjectId.get(),
        override val name: String,
        val host: String,
        val port: Int = 587,
        val username: String,
        val password: String, // Plain text!
        val useTls: Boolean = true,
        override val rateLimitConfig: RateLimitConfig = RateLimitConfig(),
        override val enabled: Boolean = true,
        override val createdAt: Instant = Instant.now(),
        override val updatedAt: Instant = Instant.now(),
        override val createdBy: String? = null
    ) : Connection()

    /**
     * OAuth2 connection.
     * Used for: Services requiring OAuth2 flow (Google, Microsoft, etc.).
     * Client secret stored as PLAIN TEXT (not production app!).
     */
    @TypeAlias("OAuth2Connection")
    data class OAuth2Connection(
        @Id override val id: ObjectId = ObjectId.get(),
        override val name: String,
        val authorizationUrl: String,
        val tokenUrl: String,
        val clientId: String,
        val clientSecret: String, // Plain text!
        val scopes: List<String> = emptyList(),
        val redirectUri: String,
        override val rateLimitConfig: RateLimitConfig = RateLimitConfig(),
        override val enabled: Boolean = true,
        override val createdAt: Instant = Instant.now(),
        override val updatedAt: Instant = Instant.now(),
        override val createdBy: String? = null
    ) : Connection()

    // Future connection types:
    // - SlackConnection
    // - TeamsConnection
    // - DiscordConnection
    // - WebSocketConnection
    // - GraphQLConnection
    // - etc.
}

/**
 * Rate limit configuration.
 * Applied per domain/host (not per connection).
 */
data class RateLimitConfig(
    val maxRequestsPerSecond: Int = 10,
    val maxRequestsPerMinute: Int = 100,
    val enabled: Boolean = true
)

/**
 * Authentication type for HTTP connections.
 */
enum class AuthType {
    NONE,
    BASIC,
    BEARER,
    API_KEY
}

/**
 * Credentials for HTTP connections (sealed class for type safety).
 */
sealed class HttpCredentials {
    data class Basic(
        val username: String,
        val password: String
    ) : HttpCredentials() {
        fun toAuthHeader(): String {
            val credentials = "$username:$password"
            val encoded = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
            return "Basic $encoded"
        }
    }

    data class Bearer(
        val token: String
    ) : HttpCredentials() {
        fun toAuthHeader(): String = "Bearer $token"
    }

    data class ApiKey(
        val headerName: String,
        val apiKey: String
    ) : HttpCredentials()
}
