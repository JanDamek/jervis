package com.jervis.entity.connection

import com.jervis.common.types.ConnectionId
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ProtocolEnum
import com.jervis.dto.connection.ProviderEnum
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Connection document - represents a connection to an external service.
 *
 * Architecture:
 * - provider: WHERE we connect (GitHub, GitLab, Atlassian, Google, etc.)
 * - protocol: HOW we communicate (HTTP, IMAP, POP3, SMTP)
 * - authType: HOW we authenticate (NONE, BASIC, BEARER, OAUTH2)
 * - capabilities: WHAT the connection can do (derived from provider/protocol)
 */
@Document(collection = "connections")
@CompoundIndexes(
    CompoundIndex(name = "name_unique_idx", def = "{'name': 1}", unique = true),
    CompoundIndex(name = "state_idx", def = "{'state': 1}"),
    CompoundIndex(name = "provider_idx", def = "{'provider': 1}"),
)
data class ConnectionDocument(
    @Id
    val id: ConnectionId = ConnectionId.generate(),
    val name: String,

    // Core architecture: provider + protocol + authType
    val provider: ProviderEnum,
    val protocol: ProtocolEnum = ProtocolEnum.HTTP,
    val authType: AuthTypeEnum = AuthTypeEnum.NONE,

    // State and rate limiting
    var state: ConnectionStateEnum = ConnectionStateEnum.NEW,
    val rateLimitConfig: RateLimitConfig = RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100),

    // Capabilities (derived from provider/protocol, stored for query optimization)
    val availableCapabilities: Set<ConnectionCapability> = emptySet(),

    // HTTP/API configuration (for DevOps providers)
    val baseUrl: String = "",
    val timeoutMs: Long = 30000,

    // Authentication credentials
    val username: String? = null,
    val password: String? = null,       // For BASIC auth or email - Plain text!
    val bearerToken: String? = null,    // For BEARER auth - Plain text!

    // OAuth2 specific
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val clientSecret: String? = null,   // Plain text!
    val scopes: List<String> = emptyList(),
    val redirectUri: String? = null,

    // Email configuration (for email providers)
    val host: String? = null,
    val port: Int = 993,
    val useSsl: Boolean = true,
    val useTls: Boolean? = null,
    val folderName: String = "INBOX",

    // Provider-specific resource identifiers (optional)
    val jiraProjectKey: String? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
    val bitbucketRepoSlug: String? = null,
    val gitRemoteUrl: String? = null,

    // Legacy fields (deprecated, kept for backwards compatibility)
    @Deprecated("Use protocol instead")
    val connectionType: ConnectionTypeEnum? = null,
    @Deprecated("Use bearerToken/username/password instead")
    val credentials: HttpCredentials? = null,
    @Deprecated("Removed - not needed")
    val gitProvider: com.jervis.domain.git.GitProviderEnum? = null,
    @Deprecated("Removed - not needed")
    val gitConfig: com.jervis.domain.git.GitConfig? = null,
) {
    fun getCapabilities(): Set<ConnectionCapability> = availableCapabilities

    // Legacy enum (deprecated)
    @Deprecated("Use ProtocolEnum from DTO instead")
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
     */
    data class RateLimitConfig(
        val maxRequestsPerSecond: Int,
        val maxRequestsPerMinute: Int,
    )

    /**
     * Legacy credentials for HTTP connections (deprecated).
     * Use authType + username/password/bearerToken instead.
     */
    @Deprecated("Use authType + username/password/bearerToken instead")
    sealed class HttpCredentials {
        abstract fun toAuthHeader(): String

        data class Basic(
            val username: String,
            val password: String,
        ) : HttpCredentials() {
            override fun toAuthHeader(): String {
                val credentials = "$username:$password"
                val encoded = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                return "Basic $encoded"
            }
        }

        data class Bearer(
            val token: String,
        ) : HttpCredentials() {
            override fun toAuthHeader(): String = "Bearer $token"
        }
    }

    /**
     * Generate Authorization header based on authType.
     */
    fun toAuthHeader(): String? {
        // First check new fields
        return when (authType) {
            AuthTypeEnum.BASIC -> {
                val user = username ?: return null
                val pass = password ?: ""
                val credentials = "$user:$pass"
                val encoded = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                "Basic $encoded"
            }
            AuthTypeEnum.BEARER -> {
                val token = bearerToken ?: return null
                "Bearer $token"
            }
            AuthTypeEnum.OAUTH2 -> {
                // OAuth2 uses bearer token after authorization
                val token = bearerToken ?: return null
                "Bearer $token"
            }
            AuthTypeEnum.NONE -> null
        } ?: credentials?.toAuthHeader() // Fallback to legacy credentials
    }
}

// Legacy extension functions (deprecated)
@Deprecated("Use AuthTypeEnum from DTO instead")
fun ConnectionDocument.HttpCredentials.toAuthType(): AuthTypeEnum =
    when (this) {
        is ConnectionDocument.HttpCredentials.Basic -> AuthTypeEnum.BASIC
        is ConnectionDocument.HttpCredentials.Bearer -> AuthTypeEnum.BEARER
    }

@Deprecated("Use ConnectionDocument.username instead")
fun ConnectionDocument.HttpCredentials.basicUsername(): String? =
    (this as? ConnectionDocument.HttpCredentials.Basic)?.username

@Deprecated("Use ConnectionDocument.password instead")
fun ConnectionDocument.HttpCredentials.basicPassword(): String? =
    (this as? ConnectionDocument.HttpCredentials.Basic)?.password

@Deprecated("Use ConnectionDocument.bearerToken instead")
fun ConnectionDocument.HttpCredentials.bearerToken(): String? =
    (this as? ConnectionDocument.HttpCredentials.Bearer)?.token
