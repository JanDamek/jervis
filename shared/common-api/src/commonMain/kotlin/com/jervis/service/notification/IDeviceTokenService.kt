package com.jervis.service.notification

import com.jervis.dto.notification.DeviceContextDto
import com.jervis.dto.notification.DeviceTokenDto
import com.jervis.dto.notification.DeviceTokenRegistrationResult
import com.jervis.dto.meeting.DeviceInfoDto
import kotlinx.rpc.annotations.Rpc

/**
 * RPC service for managing device push notification tokens.
 *
 * Two concerns, two RPCs:
 *  - [registerToken] uploads the OS-level APNs/FCM token. Client calls it
 *    once per token lifetime (on first acquisition and on token rotation).
 *    No client scope — the backend just stores token by deviceId.
 *  - [setActiveContext] tells the backend which client this device is
 *    currently scoped to. Called on every RPC reconnect so the push
 *    services can route notifications to the right recipient and queued
 *    events can flow back through the registered stream.
 */
@Rpc
interface IDeviceTokenService {
    /**
     * Register or update the OS-level push token for a device.
     * Upserts by deviceId — same device overwrites previous token.
     * Should be called ONLY when the token is freshly acquired / rotated.
     */
    suspend fun registerToken(dto: DeviceTokenDto): DeviceTokenRegistrationResult

    /**
     * Announce which client this device is currently scoped to. Expected
     * on every RPC (re)connect. Refreshes lastSeen and rewires the push
     * routing without touching the stored token blob.
     */
    suspend fun setActiveContext(dto: DeviceContextDto): DeviceTokenRegistrationResult

    /**
     * Remove a device token (e.g. on logout or app uninstall).
     */
    suspend fun unregisterToken(deviceId: String): DeviceTokenRegistrationResult

    /**
     * List all registered devices for a client.
     * Used by Meeting Helper to show available target devices.
     */
    suspend fun listDevices(clientId: String): List<DeviceInfoDto>
}
