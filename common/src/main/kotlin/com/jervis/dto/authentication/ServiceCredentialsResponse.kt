package com.jervis.dto.authentication

import kotlinx.serialization.Serializable

/**
 * Response after storing credentials
 */
@Serializable
data class ServiceCredentialsResponse(
    val credentialsRef: String,
    val success: Boolean,
    val message: String? = null,
)
