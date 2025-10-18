package com.jervis.dto.authentication

import kotlinx.serialization.Serializable

/**
 * Response with OAuth authorization URL
 */
@Serializable
data class OAuthInitResponse(
    val authorizationUrl: String,
    val state: String,
)
