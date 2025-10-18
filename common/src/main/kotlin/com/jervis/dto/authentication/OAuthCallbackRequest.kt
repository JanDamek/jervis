package com.jervis.dto.authentication

import com.jervis.domain.authentication.ServiceType
import kotlinx.serialization.Serializable

/**
 * Request to complete OAuth flow with authorization code
 */
@Serializable
data class OAuthCallbackRequest(
    val clientId: String? = null,
    val projectId: String? = null,
    val serviceType: ServiceType,
    val code: String,
    val state: String,
)
