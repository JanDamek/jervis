package com.jervis.entity.connection

import com.jervis.dto.connection.ConnectionStateEnum
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
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
    CompoundIndex(name = "state_idx", def = "{'state': 1}"),
)
sealed class Connection {
    abstract val id: ObjectId
    abstract val name: String
    var state: ConnectionStateEnum = ConnectionStateEnum.NEW
    abstract val rateLimitConfig: RateLimitConfig

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
        val credentials: HttpCredentials? = null,
        val timeoutMs: Long = 30000,
        override val rateLimitConfig: RateLimitConfig,
    ) : Connection()

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
        override val rateLimitConfig: RateLimitConfig,
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
        override val rateLimitConfig: RateLimitConfig,
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
        override val rateLimitConfig: RateLimitConfig,
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
        override val rateLimitConfig: RateLimitConfig,
    ) : Connection()
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
