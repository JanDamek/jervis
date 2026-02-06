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
