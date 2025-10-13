package com.jervis.dto

import kotlinx.serialization.Serializable

/**
 * Represents the type of external service
 */
@Serializable
enum class ServiceType {
    EMAIL,
    SLACK,
    TEAMS,
    DISCORD,
    JIRA,
    GIT,
}

/**
 * Request to store service credentials
 */
@Serializable
data class ServiceCredentialsRequest(
    val clientId: String,
    val projectId: String? = null,
    val serviceType: ServiceType,
    val credentials: ServiceCredentials,
)

/**
 * Response after storing credentials
 */
@Serializable
data class ServiceCredentialsResponse(
    val credentialsRef: String,
    val success: Boolean,
    val message: String? = null,
)

/**
 * Service credentials containing OAuth tokens or basic auth
 */
@Serializable
data class ServiceCredentials(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: String? = null,
    val tokenType: String? = null,
    val scopes: List<String>? = null,
    val username: String? = null,
    val serverId: String? = null,
    val workspace: String? = null,
    val additionalData: Map<String, String> = emptyMap(),
)

/**
 * Request to initiate OAuth flow
 */
@Serializable
data class OAuthInitRequest(
    val clientId: String,
    val projectId: String? = null,
    val serviceType: ServiceType,
    val redirectUri: String,
)

/**
 * Response with OAuth authorization URL
 */
@Serializable
data class OAuthInitResponse(
    val authorizationUrl: String,
    val state: String,
)

/**
 * Request to complete OAuth flow with authorization code
 */
@Serializable
data class OAuthCallbackRequest(
    val clientId: String,
    val projectId: String? = null,
    val serviceType: ServiceType,
    val code: String,
    val state: String,
)
