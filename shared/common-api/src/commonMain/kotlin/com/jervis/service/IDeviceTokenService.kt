package com.jervis.service

import com.jervis.dto.notification.DeviceTokenDto
import com.jervis.dto.notification.DeviceTokenRegistrationResult
import kotlinx.rpc.annotations.Rpc

/**
 * RPC service for managing device push notification tokens.
 *
 * Mobile apps register their FCM/APNs tokens here so the backend
 * can send push notifications when the app is not running.
 */
@Rpc
interface IDeviceTokenService {
    /**
     * Register or update a device token for push notifications.
     * Upserts by deviceId — same device overwrites previous token.
     */
    suspend fun registerToken(dto: DeviceTokenDto): DeviceTokenRegistrationResult

    /**
     * Remove a device token (e.g. on logout or app uninstall).
     */
    suspend fun unregisterToken(deviceId: String): DeviceTokenRegistrationResult
}
