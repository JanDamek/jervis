package com.jervis.dto.connection

import kotlinx.serialization.Serializable

/**
 * Connection capability - what functionality does this connection provide.
 * Generic capability types (vendor-agnostic).
 */
@Serializable
enum class ConnectionTypeEnum {
    HTTP,
    IMAP,
    POP3,
    SMTP,
    OAUTH2,
}

@Serializable
enum class ProviderEnum {
    GITHUB,
    GITLAB,
    ATLASSIAN,
    IMAP,
    POP3,
    SMTP,
    OAUTH2,
}

@Serializable
enum class HttpAuthTypeEnum {
    NONE,
    BASIC,
    BEARER,
}

@Serializable
enum class ConnectionCapability {
    BUGTRACKER, // Bug tracker (Jira, GitHub Issues, GitLab Issues, etc.)
    WIKI, // Wiki/documentation (Confluence, MediaWiki, Notion, etc.)
    REPOSITORY, // Code repository (GitHub, GitLab, Bitbucket, etc.)
    EMAIL_READ, // Read emails (IMAP, POP3)
    EMAIL_SEND, // Send emails (SMTP, IMAP with SMTP relay)
    GIT, // Git operations
}

/**
 * Service capabilities response - returned by each microservice to declare what it supports.
 */
@Serializable
data class ServiceCapabilitiesDto(
    val capabilities: Set<ConnectionCapability>,
)

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
    val type: ConnectionTypeEnum,
    val provider: ProviderEnum,
    val name: String,
    val state: ConnectionStateEnum,
    val baseUrl: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    // HTTP-specific (typed)
    val authType: HttpAuthTypeEnum? = null,
    val httpBasicUsername: String? = null,
    val httpBasicPassword: String? = null,
    val httpBearerToken: String? = null,
    val timeoutMs: Long? = null,
    // OAuth2 specific
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val clientSecret: String? = null, // Plain text for dev-mode
    val redirectUri: String? = null,
    val scope: String? = null,
    // Email specific (IMAP/POP3/SMTP) – dev-mode plaintext
    val password: String? = null,
    val useSsl: Boolean? = null,
    val useTls: Boolean? = null,
    val hasCredentials: Boolean = false,
    // Connection capabilities - what functionality does this connection provide
    val capabilities: Set<ConnectionCapability> = emptySet(),
    val folderName: String? = null,
    // Rate limiting
    val rateLimitConfig: RateLimitConfigDto? = null,
    // Atlassian specific
    val jiraProjectKey: String? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
    val bitbucketRepoSlug: String? = null,
    // Git specific
    val gitRemoteUrl: String? = null,
    val gitProvider: String? = null,
)

/**
 * Connection create request - sent to REST API
 */
@Serializable
data class ConnectionCreateRequestDto(
    val type: ConnectionTypeEnum,
    val provider: ProviderEnum,
    val name: String,
    val state: ConnectionStateEnum = ConnectionStateEnum.NEW,
    // Cloud flag for OAuth2 providers (GitHub, GitLab, Atlassian)
    // When true, baseUrl is not required and will be determined from provider configuration
    val isCloud: Boolean = false,
    // HTTP specific
    val baseUrl: String? = null,
    val authType: HttpAuthTypeEnum? = null,
    val httpBasicUsername: String? = null,
    val httpBasicPassword: String? = null,
    val httpBearerToken: String? = null,
    val timeoutMs: Long? = null,
    // Email-specific (IMAP/POP3/SMTP)
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null, // Plain text
    val useSsl: Boolean? = null,
    val useTls: Boolean? = null,
    // OAuth2 specific
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val clientSecret: String? = null, // Plain text
    val redirectUri: String? = null,
    val scope: String? = null,
    val folderName: String? = null,
    // Rate limiting
    val rateLimitConfig: RateLimitConfigDto? = null,
    // Atlassian specific
    val jiraProjectKey: String? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
    val bitbucketRepoSlug: String? = null,
    // Git specific
    val gitRemoteUrl: String? = null,
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
    val state: ConnectionStateEnum? = null,
    // Cloud flag for OAuth2 providers (GitHub, GitLab, Atlassian)
    // When true, baseUrl is not required and will be determined from provider configuration
    val isCloud: Boolean? = null,
    // HTTP specific
    val baseUrl: String? = null,
    val authType: HttpAuthTypeEnum? = null,
    val httpBasicUsername: String? = null,
    val httpBasicPassword: String? = null,
    val httpBearerToken: String? = null,
    val timeoutMs: Long? = null,
    // Email-specific (IMAP/POP3/SMTP)
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val scope: String? = null,
    // OAuth2 specific
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val clientSecret: String? = null,
    val redirectUri: String? = null,
    val useSsl: Boolean? = null,
    val useTls: Boolean? = null,
    val folderName: String? = null,
    // Rate limiting
    val rateLimitConfig: RateLimitConfigDto? = null,
    // Atlassian specific
    val jiraProjectKey: String? = null,
    val confluenceSpaceKey: String? = null,
    val confluenceRootPageId: String? = null,
    val bitbucketRepoSlug: String? = null,
    // Git specific
    val gitRemoteUrl: String? = null,
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
