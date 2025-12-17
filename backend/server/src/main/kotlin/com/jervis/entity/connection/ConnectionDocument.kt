package com.jervis.entity.connection

import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.types.ConnectionId
import org.springframework.data.annotation.Id
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
data class ConnectionDocument(
    @Id
    val id: ConnectionId = ConnectionId.generate(),
    val name: String,
    var state: ConnectionStateEnum = ConnectionStateEnum.NEW,
    val rateLimitConfig: RateLimitConfig = RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100),
    val baseUrl: String = "",
    val credentials: HttpCredentials? = null,
    val timeoutMs: Long = 30000,
    val pollingStates: Map<String, PollingState> = emptyMap(),
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
) {
    enum class ConnectionTypeEnum {
        HTTP,
        IMAP,
        POP3,
        SMTP,
        OAUTH2,
    }

    class PollingState(
        var lastFetchedUid: Long? = null,
        var lastFetchedMessageNumber: Int? = null,
        var lastSeenUpdatedAt: Instant? = null,
    )

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

fun ConnectionDocument.HttpCredentials.toAuthType(): String =
    when (this) {
        is ConnectionDocument.HttpCredentials.Basic -> "BASIC"
        is ConnectionDocument.HttpCredentials.Bearer -> "BEARER"
    }

fun ConnectionDocument.HttpCredentials.basicUsername(): String? = (this as? ConnectionDocument.HttpCredentials.Basic)?.username

fun ConnectionDocument.HttpCredentials.basicPassword(): String? = (this as? ConnectionDocument.HttpCredentials.Basic)?.password

fun ConnectionDocument.HttpCredentials.bearerToken(): String? = (this as? ConnectionDocument.HttpCredentials.Bearer)?.token
