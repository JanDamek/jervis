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
    AZURE_DEVOPS,
    GOOGLE_CLOUD,    // Google Cloud (Source Repos, Issue Tracker)

    // Email providers
    GOOGLE_WORKSPACE,  // Gmail, Google Workspace
    MICROSOFT_365,     // Outlook, Office 365
    GENERIC_EMAIL,     // Generic IMAP/POP3/SMTP server (Zimbra, Exchange on-prem, etc.)
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
    REPOSITORY,   // Git repo + PR/MR + branches + everything
    BUGTRACKER,   // Issues, tickets
    WIKI,         // Wiki pages, documentation
    EMAIL_READ,   // Read emails
    EMAIL_SEND,   // Send emails
}

// =============================================================================
// Backwards compatibility aliases (deprecated, will be removed)
// =============================================================================

@Deprecated("Use ProtocolEnum instead", ReplaceWith("ProtocolEnum"))
typealias ConnectionTypeEnum = ProtocolEnum

@Deprecated("Use AuthTypeEnum instead", ReplaceWith("AuthTypeEnum"))
typealias HttpAuthTypeEnum = AuthTypeEnum

/**
 * Service capabilities response - returned by each microservice to declare what it supports.
 */
@Serializable
data class ServiceCapabilitiesDto(
    val capabilities: Set<ConnectionCapability>,
)

/**
 * Provider capabilities mapping - what each provider supports.
 */
object ProviderCapabilities {
    private val DEVOPS_CAPABILITIES = setOf(
        ConnectionCapability.REPOSITORY,
        ConnectionCapability.BUGTRACKER,
        ConnectionCapability.WIKI
    )

    private val EMAIL_CAPABILITIES = setOf(
        ConnectionCapability.EMAIL_READ,
        ConnectionCapability.EMAIL_SEND
    )

    fun forProvider(provider: ProviderEnum): Set<ConnectionCapability> = when (provider) {
        ProviderEnum.GITHUB -> DEVOPS_CAPABILITIES
        ProviderEnum.GITLAB -> DEVOPS_CAPABILITIES
        ProviderEnum.ATLASSIAN -> DEVOPS_CAPABILITIES
        ProviderEnum.AZURE_DEVOPS -> DEVOPS_CAPABILITIES
        ProviderEnum.GOOGLE_CLOUD -> DEVOPS_CAPABILITIES

        ProviderEnum.GOOGLE_WORKSPACE -> EMAIL_CAPABILITIES
        ProviderEnum.MICROSOFT_365 -> EMAIL_CAPABILITIES
        ProviderEnum.GENERIC_EMAIL -> EMAIL_CAPABILITIES
    }

    /**
     * Get capabilities based on provider and protocol.
     * For email providers, protocol determines read/send capabilities.
     */
    fun forProviderAndProtocol(provider: ProviderEnum, protocol: ProtocolEnum): Set<ConnectionCapability> {
        return when (provider) {
            // DevOps providers always use HTTP and have all capabilities
            ProviderEnum.GITHUB,
            ProviderEnum.GITLAB,
            ProviderEnum.ATLASSIAN,
            ProviderEnum.AZURE_DEVOPS,
            ProviderEnum.GOOGLE_CLOUD -> DEVOPS_CAPABILITIES

            // Email providers - capabilities depend on protocol
            ProviderEnum.GOOGLE_WORKSPACE,
            ProviderEnum.MICROSOFT_365,
            ProviderEnum.GENERIC_EMAIL -> when (protocol) {
                ProtocolEnum.IMAP -> setOf(ConnectionCapability.EMAIL_READ, ConnectionCapability.EMAIL_SEND)
                ProtocolEnum.POP3 -> setOf(ConnectionCapability.EMAIL_READ)
                ProtocolEnum.SMTP -> setOf(ConnectionCapability.EMAIL_SEND)
                ProtocolEnum.HTTP -> emptySet() // Email providers don't use HTTP
            }
        }
    }

    /**
     * Get available protocols for a provider.
     */
    fun protocolsForProvider(provider: ProviderEnum): Set<ProtocolEnum> = when (provider) {
        ProviderEnum.GITHUB,
        ProviderEnum.GITLAB,
        ProviderEnum.ATLASSIAN,
        ProviderEnum.AZURE_DEVOPS,
        ProviderEnum.GOOGLE_CLOUD -> setOf(ProtocolEnum.HTTP)

        ProviderEnum.GOOGLE_WORKSPACE,
        ProviderEnum.MICROSOFT_365 -> setOf(ProtocolEnum.IMAP, ProtocolEnum.SMTP)

        ProviderEnum.GENERIC_EMAIL -> setOf(ProtocolEnum.IMAP, ProtocolEnum.POP3, ProtocolEnum.SMTP)
    }

    /**
     * Get available auth types for a provider.
     */
    fun authTypesForProvider(provider: ProviderEnum): Set<AuthTypeEnum> = when (provider) {
        ProviderEnum.GITHUB -> setOf(AuthTypeEnum.BEARER, AuthTypeEnum.OAUTH2)
        ProviderEnum.GITLAB -> setOf(AuthTypeEnum.BEARER, AuthTypeEnum.OAUTH2)
        ProviderEnum.ATLASSIAN -> setOf(AuthTypeEnum.BASIC, AuthTypeEnum.OAUTH2)
        ProviderEnum.AZURE_DEVOPS -> setOf(AuthTypeEnum.BEARER, AuthTypeEnum.OAUTH2)
        ProviderEnum.GOOGLE_CLOUD -> setOf(AuthTypeEnum.OAUTH2)

        ProviderEnum.GOOGLE_WORKSPACE -> setOf(AuthTypeEnum.OAUTH2)
        ProviderEnum.MICROSOFT_365 -> setOf(AuthTypeEnum.OAUTH2, AuthTypeEnum.BASIC)
        ProviderEnum.GENERIC_EMAIL -> setOf(AuthTypeEnum.BASIC)
    }

    /**
     * Check if provider is a DevOps provider.
     */
    fun isDevOpsProvider(provider: ProviderEnum): Boolean = when (provider) {
        ProviderEnum.GITHUB,
        ProviderEnum.GITLAB,
        ProviderEnum.ATLASSIAN,
        ProviderEnum.AZURE_DEVOPS,
        ProviderEnum.GOOGLE_CLOUD -> true
        else -> false
    }

    /**
     * Check if provider is an email provider.
     */
    fun isEmailProvider(provider: ProviderEnum): Boolean = when (provider) {
        ProviderEnum.GOOGLE_WORKSPACE,
        ProviderEnum.MICROSOFT_365,
        ProviderEnum.GENERIC_EMAIL -> true
        else -> false
    }
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

    // Legacy compatibility
    @Deprecated("Use capabilities instead")
    val hasCredentials: Boolean = false,
    @Deprecated("Use protocol instead")
    val type: ProtocolEnum? = null,
    @Deprecated("Use bearerToken instead")
    val httpBearerToken: String? = null,
    @Deprecated("Use username instead")
    val httpBasicUsername: String? = null,
    @Deprecated("Use password instead")
    val httpBasicPassword: String? = null,
    @Deprecated("Removed - not needed")
    val gitProvider: String? = null,
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

    // Legacy compatibility
    @Deprecated("Use protocol instead")
    val type: ProtocolEnum? = null,
    @Deprecated("Use bearerToken instead")
    val httpBearerToken: String? = null,
    @Deprecated("Use username instead")
    val httpBasicUsername: String? = null,
    @Deprecated("Use password instead")
    val httpBasicPassword: String? = null,
    @Deprecated("Removed - not needed")
    val gitProvider: String? = null,
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

    // Legacy compatibility
    @Deprecated("Use bearerToken instead")
    val httpBearerToken: String? = null,
    @Deprecated("Use username instead")
    val httpBasicUsername: String? = null,
    @Deprecated("Use password instead")
    val httpBasicPassword: String? = null,
    @Deprecated("Removed - not needed")
    val gitProvider: String? = null,
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
