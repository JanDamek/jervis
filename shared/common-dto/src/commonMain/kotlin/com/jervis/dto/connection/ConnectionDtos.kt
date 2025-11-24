package com.jervis.dto.connection

import kotlinx.serialization.Serializable

/**
 * Connection response DTO - returned from REST API
 */
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
    val createdAtMs: Long,  // Epoch millis
    val updatedAtMs: Long,  // Epoch millis
)

/**
 * Connection create request - sent to REST API
 */
@Serializable
data class ConnectionCreateRequestDto(
    val type: String, // HTTP, IMAP, POP3, SMTP, OAUTH2
    val name: String,
    val enabled: Boolean = true,

    // HTTP specific
    val baseUrl: String? = null,
    val authType: String? = null,
    val credentials: String? = null, // Plain text: "username:password" or "token"
    val timeoutMs: Long? = null,

    // Email specific (IMAP/POP3/SMTP)
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null, // Plain text
    val useSsl: Boolean? = null,
    val useTls: Boolean? = null,

    // OAuth2 specific
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val clientId: String? = null,
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
    val enabled: Boolean? = null,

    // HTTP specific
    val baseUrl: String? = null,
    val credentials: String? = null,
    val timeoutMs: Long? = null,

    // Email specific (IMAP/POP3/SMTP)
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
