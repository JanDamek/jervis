package com.jervis.dto.notification

import kotlinx.serialization.Serializable

/**
 * DTO for registering a device push notification token.
 */
@Serializable
data class DeviceTokenDto(
    val clientId: String,
    val token: String,
    val platform: String,
    val deviceId: String,
)

/**
 * Result of device token registration/unregistration.
 */
@Serializable
data class DeviceTokenRegistrationResult(
    val success: Boolean,
    val message: String? = null,
)
