package com.jervis.dto.connection

import kotlinx.serialization.Serializable

/**
 * Connection capability - what functionality does this connection provide.
 * Generic capability types (vendor-agnostic).
 */
@Serializable
enum class ConnectionCapability {
    BUGTRACKER,  // Bug tracker (Jira, GitHub Issues, GitLab Issues, etc.)
    WIKI,        // Wiki/documentation (Confluence, MediaWiki, Notion, etc.)
    REPOSITORY,  // Code repository (GitHub, GitLab, Bitbucket, etc.)
    EMAIL,       // Email (IMAP, POP3, SMTP)
    GIT,         // Git operations
}

/**
 * Connection response DTO - returned from REST API
 */
@Serializable
data class ConnectionResponseDto(
    val id: String,
    val type: String, // HTTP, IMAP, POP3, SMTP, OAUTH2
    val name: String,
    val state: ConnectionStateEnum,
    val baseUrl: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    // HTTP-specific (typed)
    val authType: String? = null, // NONE, BASIC, BEARER
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
)

/**
 * Connection create request - sent to REST API
 */
@Serializable
data class ConnectionCreateRequestDto(
    val type: String, // HTTP, IMAP, POP3, SMTP, OAUTH2
    val name: String,
    val state: ConnectionStateEnum = ConnectionStateEnum.NEW,
    // HTTP specific
    val baseUrl: String? = null,
    val authType: String? = null, // NONE, BASIC, BEARER
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
)

/**
 * Connection update request - sent to REST API
 * All fields are optional - only provided fields will be updated
 */
@Serializable
data class ConnectionUpdateRequestDto(
    val name: String? = null,
    val state: ConnectionStateEnum? = null,
    // HTTP specific
    val baseUrl: String? = null,
    val authType: String? = null, // NONE, BASIC, BEARER
    val httpBasicUsername: String? = null,
    val httpBasicPassword: String? = null,
    val httpBearerToken: String? = null,
    val timeoutMs: Long? = null,
    // Email-specific (IMAP/POP3/SMTP)
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,
    // OAuth2 specific
    val clientSecret: String? = null,
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
