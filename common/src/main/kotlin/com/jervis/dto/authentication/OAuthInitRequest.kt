package com.jervis.dto.authentication

import com.jervis.domain.authentication.ServiceType
import kotlinx.serialization.Serializable

/**
 * Request to initiate OAuth flow
 */
@Serializable
data class OAuthInitRequest(
    val clientId: String? = null,
    val projectId: String? = null,
    val serviceType: ServiceType,
    val redirectUri: String,
)
