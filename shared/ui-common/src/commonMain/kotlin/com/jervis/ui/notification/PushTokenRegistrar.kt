package com.jervis.ui.notification

import com.jervis.service.notification.IDeviceTokenService

/**
 * Platform-specific push token registration.
 *
 * Two concerns, two functions:
 *  - [registerTokenIfNeeded] uploads the OS-level APNs/FCM token once
 *    per token lifetime. Dedup by last-seen token value.
 *  - [announceContext] tells the backend which client this device is
 *    currently scoped to. Call on every RPC (re)connect — cheap,
 *    refreshes lastSeen and rewires push routing without resending
 *    the token blob.
 *
 * On Android: retrieves FCM token, registers with backend.
 * On iOS: waits for APNs token from Swift, registers with backend.
 * On JVM: no push token (desktop uses kRPC event streams),
 *         but context announcement still useful for device registry.
 */
expect object PushTokenRegistrar {
    /** Upload OS-level push token — call only when token is first
     *  acquired or has rotated. */
    suspend fun registerTokenIfNeeded(deviceTokenService: IDeviceTokenService)

    /** Announce current client scope — call on every RPC reconnect. */
    suspend fun announceContext(clientId: String, deviceTokenService: IDeviceTokenService)
}
