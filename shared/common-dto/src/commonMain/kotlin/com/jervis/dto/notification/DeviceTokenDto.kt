package com.jervis.dto.notification

import kotlinx.serialization.Serializable

/**
 * Payload for registering the OS-level push-notification token.
 *
 * Sent ONCE when the OS hands out a token (or when it rotates). Has no
 * clientId — the device → client binding lives in [DeviceContextDto],
 * which is updated on every RPC reconnect.
 */
@Serializable
data class DeviceTokenDto(
    val deviceId: String,
    val token: String,
    val platform: String,
    val deviceName: String = "",
    val deviceType: String = "UNKNOWN",
    val capabilities: List<String> = emptyList(),
)

/**
 * Payload for announcing which client a given device is currently scoped
 * to. Sent on every RPC (re)connect so the backend's `registered flow`
 * can push events / queued content to the correct client and the `lastSeen`
 * bookkeeping stays fresh without re-uploading the token blob.
 */
@Serializable
data class DeviceContextDto(
    val deviceId: String,
    val clientId: String,
)

/**
 * Result of device token registration/unregistration or context update.
 */
@Serializable
data class DeviceTokenRegistrationResult(
    val success: Boolean,
    val message: String? = null,
)
