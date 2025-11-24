package com.jervis.dto.connection

import com.jervis.entity.connection.AuthType
import com.jervis.entity.connection.Connection
import com.jervis.entity.connection.RateLimitConfig
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.time.Instant

@Serializable
data class ConnectionResponseDto(
    val id: String,
    val type: String, // HTTP, IMAP, POP3, SMTP, OAUTH2
    val name: String,
    val enabled: Boolean,
    val baseUrl: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val authType: String? = null,
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val clientId: String? = null,
    val hasCredentials: Boolean = false,
    @kotlinx.serialization.Contextual
    val rateLimitConfig: RateLimitConfig? = null,
    val createdAtMs: Long,  // Epoch millis - no serializer needed!
    val updatedAtMs: Long,  // Epoch millis - no serializer needed!
) {
    val createdAt: Instant get() = Instant.ofEpochMilli(createdAtMs)
    val updatedAt: Instant get() = Instant.ofEpochMilli(updatedAtMs)
}

@Serializable
data class ConnectionCreateRequestDto(
    val type: String, // HTTP, IMAP, POP3, SMTP, OAUTH2
    val name: String,
    val enabled: Boolean = true,

    // HTTP specific
    val baseUrl: String? = null,
    val authType: String? = null,
    val credentials: String? = null, // Plain text, will be encrypted
    val timeoutMs: Long? = null,
    @kotlinx.serialization.Contextual
    val rateLimitConfig: RateLimitConfig? = null,

    // Email specific (IMAP/POP3/SMTP)
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null, // Plain text, will be encrypted
    val useSsl: Boolean? = null,
    val useTls: Boolean? = null,

    // OAuth2 specific
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null, // Plain text, will be encrypted
    val redirectUri: String? = null,
    val scope: String? = null,
) {
    fun toEntity(): Connection {
        return when (type.uppercase()) {
            "HTTP" -> Connection.HttpConnection(
                name = name,
                baseUrl = baseUrl ?: throw IllegalArgumentException("baseUrl required for HTTP connection"),
                authType = authType?.let { AuthType.valueOf(it) } ?: AuthType.NONE,
                credentials = credentials, // Plain text!
                timeoutMs = timeoutMs ?: 30000,
                rateLimitConfig = rateLimitConfig ?: RateLimitConfig(),
                enabled = enabled,
            )
            "IMAP" -> Connection.ImapConnection(
                name = name,
                host = host ?: throw IllegalArgumentException("host required for IMAP connection"),
                port = port ?: 993,
                username = username ?: throw IllegalArgumentException("username required for IMAP connection"),
                password = password ?: throw IllegalArgumentException("password required for IMAP connection"), // Plain text!
                useSsl = useSsl ?: true,
                enabled = enabled,
            )
            "POP3" -> Connection.Pop3Connection(
                name = name,
                host = host ?: throw IllegalArgumentException("host required for POP3 connection"),
                port = port ?: 995,
                username = username ?: throw IllegalArgumentException("username required for POP3 connection"),
                password = password ?: throw IllegalArgumentException("password required for POP3 connection"), // Plain text!
                useSsl = useSsl ?: true,
                enabled = enabled,
            )
            "SMTP" -> Connection.SmtpConnection(
                name = name,
                host = host ?: throw IllegalArgumentException("host required for SMTP connection"),
                port = port ?: 587,
                username = username ?: throw IllegalArgumentException("username required for SMTP connection"),
                password = password ?: throw IllegalArgumentException("password required for SMTP connection"), // Plain text!
                enabled = enabled,
            )
            "OAUTH2" -> Connection.OAuth2Connection(
                name = name,
                authorizationUrl = authorizationUrl ?: throw IllegalArgumentException("authorizationUrl required for OAuth2 connection"),
                tokenUrl = tokenUrl ?: throw IllegalArgumentException("tokenUrl required for OAuth2 connection"),
                clientId = clientId ?: throw IllegalArgumentException("clientId required for OAuth2 connection"),
                clientSecret = clientSecret ?: throw IllegalArgumentException("clientSecret required for OAuth2 connection"), // Plain text!
                redirectUri = redirectUri ?: throw IllegalArgumentException("redirectUri required for OAuth2 connection"),
                scopes = listOf(scope ?: ""),
                enabled = enabled,
            )
            else -> throw IllegalArgumentException("Unknown connection type: $type")
        }
    }
}

@Serializable
data class ConnectionUpdateRequestDto(
    val name: String? = null,
    val enabled: Boolean? = null,

    // HTTP specific
    val baseUrl: String? = null,
    val credentials: String? = null,
    @kotlinx.serialization.Contextual
    val rateLimitConfig: RateLimitConfig? = null,
    val timeoutMs: Long? = null,

    // Email specific (IMAP/POP3/SMTP)
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,

    // OAuth2 specific
    val clientSecret: String? = null,
) {
    fun applyTo(existing: Connection): Connection {
        return when (existing) {
            is Connection.HttpConnection -> existing.copy(
                name = name ?: existing.name,
                enabled = enabled ?: existing.enabled,
                baseUrl = baseUrl ?: existing.baseUrl,
                credentials = credentials ?: existing.credentials, // Plain text!
                rateLimitConfig = rateLimitConfig ?: existing.rateLimitConfig,
                timeoutMs = timeoutMs ?: existing.timeoutMs,
                updatedAt = Instant.now()
            )
            is Connection.ImapConnection -> existing.copy(
                name = name ?: existing.name,
                enabled = enabled ?: existing.enabled,
                host = host ?: existing.host,
                port = port ?: existing.port,
                username = username ?: existing.username,
                password = password ?: existing.password, // Plain text!
                updatedAt = Instant.now()
            )
            is Connection.Pop3Connection -> existing.copy(
                name = name ?: existing.name,
                enabled = enabled ?: existing.enabled,
                host = host ?: existing.host,
                port = port ?: existing.port,
                username = username ?: existing.username,
                password = password ?: existing.password, // Plain text!
                updatedAt = Instant.now()
            )
            is Connection.SmtpConnection -> existing.copy(
                name = name ?: existing.name,
                enabled = enabled ?: existing.enabled,
                host = host ?: existing.host,
                port = port ?: existing.port,
                username = username ?: existing.username,
                password = password ?: existing.password, // Plain text!
                updatedAt = Instant.now()
            )
            is Connection.OAuth2Connection -> existing.copy(
                name = name ?: existing.name,
                enabled = enabled ?: existing.enabled,
                clientSecret = clientSecret ?: existing.clientSecret, // Plain text!
                updatedAt = Instant.now()
            )
        }
    }
}

@Serializable
data class ConnectionTestResultDto(
    val success: Boolean,
    val message: String? = null,
    val details: Map<String, String>? = null,
)
