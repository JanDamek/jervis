package com.jervis.dto.connection

import kotlinx.serialization.Serializable

/**
 * Provider - WHERE we connect (service/vendor).
 * Determines available capabilities and provider-specific configuration.
 */
@Serializable
enum class ProviderEnum {
    // DevOps platforms
    GITHUB,
    GITLAB,
    ATLASSIAN,       // Jira + Confluence + Bitbucket

    // Email providers
    GOOGLE_WORKSPACE,  // Gmail / Google Workspace (OAuth2)
    GENERIC_EMAIL,     // IMAP/POP3/SMTP (Outlook, Zimbra, Exchange, atd.)

    // Chat platforms
    SLACK,
    MICROSOFT_TEAMS,   // OAuth2 / Browser Session (K8s pod) / Local token
    DISCORD,
}

/**
 * Protocol - HOW we communicate (transport layer).
 */
@Serializable
enum class ProtocolEnum {
    HTTP,   // REST API over HTTP/HTTPS
    IMAP,   // Email receiving
    POP3,   // Email receiving (legacy)
    SMTP,   // Email sending
}

/**
 * Authentication type - HOW we authenticate.
 */
@Serializable
enum class AuthTypeEnum {
    NONE,       // No authentication
    BASIC,      // Username + password
    BEARER,     // Token (API key, personal access token)
    OAUTH2,     // OAuth 2.0 flow
}

/**
 * Connection capability - WHAT the connection can do.
 * Derived from provider, not manually configured.
 */
@Serializable
enum class ConnectionCapability {
    REPOSITORY,      // Git repo + PR/MR + branches + everything
    BUGTRACKER,      // Issues, tickets
    WIKI,            // Wiki pages, documentation
    EMAIL_READ,      // Read emails
    EMAIL_SEND,      // Send emails
    CHAT_READ,       // Read chat messages (Slack/Teams/Discord)
    CHAT_SEND,       // Send chat messages
    CALENDAR_READ,   // Read calendar events
    CALENDAR_WRITE,  // Create/update calendar events
}

/**
 * Rate limit configuration DTO.
 */
@Serializable
data class RateLimitConfigDto(
    val maxRequestsPerSecond: Int,
    val maxRequestsPerMinute: Int,
)

/**
 * Connection response DTO - returned from REST API
 */
@Serializable
data class ConnectionResponseDto(
    val id: String,
    val provider: ProviderEnum,
    val protocol: ProtocolEnum,
    val authType: AuthTypeEnum,
    val name: String,
    val state: ConnectionStateEnum,

    // Connection capabilities - derived from provider/protocol
    val capabilities: Set<ConnectionCapability> = emptySet(),

    // Cloud flag - when true, provider uses its default cloud URL
    val isCloud: Boolean = false,

    // HTTP/API configuration (for DevOps providers)
    val baseUrl: String? = null,
    val timeoutMs: Long? = null,

    // Authentication credentials
    val username: String? = null,
    val password: String? = null,       // For BASIC auth or email
    val bearerToken: String? = null,    // For BEARER auth

    // OAuth2 specific
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val clientSecret: String? = null,
    val redirectUri: String? = null,
    val scope: String? = null,

    // Email configuration (for email providers)
    val host: String? = null,
    val port: Int? = null,
    val useSsl: Boolean? = null,
    val useTls: Boolean? = null,
    val folderName: String? = null,

    // Rate limiting
    val rateLimitConfig: RateLimitConfigDto? = null,

    // Provider-specific resource identifiers (optional)
    val jiraProjectKey: String? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
    val bitbucketRepoSlug: String? = null,
    val gitRemoteUrl: String? = null,

    // O365 Gateway (browser session relay)
    val o365ClientId: String? = null,

    // Jervis self-identity — who this connection authenticates as
    val selfUsername: String? = null,
    val selfDisplayName: String? = null,
    val selfId: String? = null,
    val selfEmail: String? = null,

    // Whether this connection belongs to Jervis itself (vs. client's human account)
    val isJervisOwned: Boolean = false,
)

/**
 * Connection create request - sent to REST API
 */
@Serializable
data class ConnectionCreateRequestDto(
    val provider: ProviderEnum,
    val protocol: ProtocolEnum,
    val authType: AuthTypeEnum,
    val name: String,
    val state: ConnectionStateEnum = ConnectionStateEnum.NEW,

    // Cloud flag for OAuth2 providers (GitHub, GitLab, Atlassian)
    val isCloud: Boolean = false,

    // HTTP/API configuration (for DevOps providers)
    val baseUrl: String? = null,
    val timeoutMs: Long? = null,

    // Authentication credentials
    val username: String? = null,
    val password: String? = null,
    val bearerToken: String? = null,

    // OAuth2 specific
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val clientSecret: String? = null,
    val redirectUri: String? = null,
    val scope: String? = null,

    // Email configuration (for email providers)
    val host: String? = null,
    val port: Int? = null,
    val useSsl: Boolean? = null,
    val useTls: Boolean? = null,
    val folderName: String? = null,

    // Rate limiting
    val rateLimitConfig: RateLimitConfigDto? = null,

    // Provider-specific resource identifiers (optional)
    val jiraProjectKey: String? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
    val bitbucketRepoSlug: String? = null,
    val gitRemoteUrl: String? = null,

    // O365 Gateway (browser session relay)
    val o365ClientId: String? = null,

    // Whether this connection belongs to Jervis itself (vs. client's human account)
    val isJervisOwned: Boolean = false,
)

/**
 * Connection update request - sent to REST API
 * All fields are optional - only provided fields will be updated
 */
@Serializable
data class ConnectionUpdateRequestDto(
    val name: String? = null,
    val provider: ProviderEnum? = null,
    val protocol: ProtocolEnum? = null,
    val authType: AuthTypeEnum? = null,
    val state: ConnectionStateEnum? = null,

    // Cloud flag for OAuth2 providers
    val isCloud: Boolean? = null,

    // HTTP/API configuration
    val baseUrl: String? = null,
    val timeoutMs: Long? = null,

    // Authentication credentials
    val username: String? = null,
    val password: String? = null,
    val bearerToken: String? = null,

    // OAuth2 specific
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val clientSecret: String? = null,
    val redirectUri: String? = null,
    val scope: String? = null,

    // Email configuration
    val host: String? = null,
    val port: Int? = null,
    val useSsl: Boolean? = null,
    val useTls: Boolean? = null,
    val folderName: String? = null,

    // Rate limiting
    val rateLimitConfig: RateLimitConfigDto? = null,

    // Provider-specific resource identifiers
    val jiraProjectKey: String? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
    val bitbucketRepoSlug: String? = null,
    val gitRemoteUrl: String? = null,

    // O365 Gateway (browser session relay)
    val o365ClientId: String? = null,

    // Whether this connection belongs to Jervis itself (vs. client's human account)
    val isJervisOwned: Boolean? = null,
)

/**
 * Connection test result - returned from test endpoint
 */
@Serializable
data class ConnectionTestResultDto(
    val success: Boolean,
    val message: String? = null,
    val details: Map<String, String>? = null,
)

/**
 * Importable project from external connection (GitHub, GitLab, etc.)
 */
@Serializable
data class ConnectionImportProjectDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val url: String? = null,
)

/**
 * Browser session status for Teams Browser Session connections.
 * Used by UI to show login progress dialog.
 */
@Serializable
data class BrowserSessionStatusDto(
    val state: String, // PENDING_LOGIN, ACTIVE, AWAITING_MFA, EXPIRED, ERROR
    val hasToken: Boolean = false,
    val vncUrl: String? = null,
    val message: String? = null,
    val mfaType: String? = null,     // authenticator_code, authenticator_number, sms_code
    val mfaMessage: String? = null,  // Human-readable MFA instruction
    val mfaNumber: String? = null,   // Number to approve in authenticator app
)

/**
 * Available resource from a connection for a given capability.
 * Used to populate dropdowns in UI for selecting which resources to index.
 */
@Serializable
data class ConnectionResourceDto(
    /** Unique identifier for this resource (e.g., project key, folder name, repo slug) */
    val id: String,
    /** Human-readable name */
    val name: String,
    /** Description or additional context */
    val description: String? = null,
    /** The capability this resource belongs to */
    val capability: ConnectionCapability,
)
