package com.jervis.dto.authentication

import kotlinx.serialization.Serializable

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
