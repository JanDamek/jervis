package com.jervis.entity.connection

import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.types.ConnectionId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

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
sealed class ConnectionDocument {
    abstract val id: ConnectionId
    abstract val name: String
    abstract var state: ConnectionStateEnum
    abstract val rateLimitConfig: RateLimitConfig

    /**
     * HTTP/REST API connection.
     * Used for: Atlassian (Jira/Confluence), Link Scraper, any REST API.
     * Credentials stored as PLAIN TEXT (not production app!).
     *
     * pollingStates: Map of tool name ("JIRA", "CONFLUENCE") to polling state.
     * One connection can serve multiple tools (e.g., Jira + Confluence from same Atlassian instance).
     */
    @TypeAlias("HttpConnectionDocument")
    data class HttpConnectionDocument(
        @Id override val id: ConnectionId = ConnectionId.generate(),
        override val name: String,
        val baseUrl: String,
        val credentials: HttpCredentials? = null,
        val timeoutMs: Long = 30000,
        override val rateLimitConfig: RateLimitConfig,
        override var state: ConnectionStateEnum = ConnectionStateEnum.NEW,
        val pollingStates: Map<String, PollingState.Http> = emptyMap(),
    ) : ConnectionDocument()

    /**
     * IMAP email connection.
     * Used for: Email receiving (Gmail, Outlook, custom IMAP servers).
     * Password stored as PLAIN TEXT (not production app!).
     */
    @TypeAlias("ImapConnectionDocument")
    data class ImapConnectionDocument(
        @Id override val id: ConnectionId = ConnectionId.generate(),
        override val name: String,
        val host: String,
        val port: Int = 993,
        val username: String,
        val password: String, // Plain text!
        val useSsl: Boolean = true,
        val folderName: String = "INBOX",
        override val rateLimitConfig: RateLimitConfig,
        override var state: ConnectionStateEnum = ConnectionStateEnum.NEW,
        val pollingState: PollingState.Imap? = null,
    ) : ConnectionDocument()

    /**
     * POP3 email connection.
     * Used for: Email receiving (alternative to IMAP).
     * Password stored as PLAIN TEXT (not production app!).
     */
    @TypeAlias("Pop3ConnectionDocument")
    data class Pop3ConnectionDocument(
        @Id override val id: ConnectionId = ConnectionId.generate(),
        override val name: String,
        val host: String,
        val port: Int = 995,
        val username: String,
        val password: String, // Plain text!
        val useSsl: Boolean = true,
        override val rateLimitConfig: RateLimitConfig,
        override var state: ConnectionStateEnum = ConnectionStateEnum.NEW,
        val pollingState: PollingState.Pop3? = null,
    ) : ConnectionDocument()

    /**
     * SMTP email connection.
     * Used for: Email sending.
     * Password stored as PLAIN TEXT (not production app!).
     */
    @TypeAlias("SmtpConnectionDocument")
    data class SmtpConnectionDocument(
        @Id override val id: ConnectionId = ConnectionId.generate(),
        override val name: String,
        val host: String,
        val port: Int = 587,
        val username: String,
        val password: String, // Plain text!
        val useTls: Boolean = true,
        override var state: ConnectionStateEnum = ConnectionStateEnum.NEW,
        override val rateLimitConfig: RateLimitConfig,
    ) : ConnectionDocument()

    /**
     * OAuth2 connection.
     * Used for: Services requiring OAuth2 flow (Google, Microsoft, etc.).
     * Client secret stored as PLAIN TEXT (not production app!).
     */
    @TypeAlias("OAuth2ConnectionDocument")
    data class OAuth2ConnectionDocument(
        @Id override val id: ConnectionId = ConnectionId.generate(),
        override val name: String,
        val authorizationUrl: String,
        val tokenUrl: String,
        val clientId: String,
        val clientSecret: String, // Plain text!
        val scopes: List<String> = emptyList(),
        val redirectUri: String,
        override var state: ConnectionStateEnum = ConnectionStateEnum.NEW,
        override val rateLimitConfig: RateLimitConfig,
    ) : ConnectionDocument()
}

sealed class PollingState {
    /**
     * IMAP polling state - tracks last fetched UID.
     */
    @TypeAlias("ImapPollingState")
    data class Imap(
        val lastFetchedUid: Long,
    ) : PollingState()

    /**
     * POP3 polling state - tracks last fetched message number.
     */
    @TypeAlias("Pop3PollingState")
    data class Pop3(
        val lastFetchedMessageNumber: Int,
    ) : PollingState()

    /**
     * HTTP polling state - tracks last seen updated timestamp.
     * Used for Atlassian (Jira, Confluence) and other time-based polling.
     * Stored in HttpConnectionDocument.pollingStates map, keyed by tool name ("JIRA", "CONFLUENCE").
     */
    @TypeAlias("HttpPollingState")
    data class Http(
        val lastSeenUpdatedAt: Instant,
    ) : PollingState()
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
