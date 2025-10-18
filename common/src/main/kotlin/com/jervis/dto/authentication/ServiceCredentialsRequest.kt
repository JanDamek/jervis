package com.jervis.dto.authentication

import com.jervis.domain.authentication.ServiceType
import kotlinx.serialization.Serializable

/**
 * Request to store service credentials
 */
@Serializable
data class ServiceCredentialsRequest(
    val clientId: String? = null,
    val projectId: String? = null,
    val serviceType: ServiceType,
    val credentials: ServiceCredentials,
)
