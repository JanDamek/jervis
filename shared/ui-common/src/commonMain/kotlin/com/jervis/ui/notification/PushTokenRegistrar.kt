package com.jervis.ui.notification

import com.jervis.service.IDeviceTokenService

/**
 * Platform-specific push token registration.
 *
 * On Android: retrieves FCM token, registers with backend.
 * On iOS: waits for APNs token from Swift, registers with backend.
 * On JVM: registers desktop device (no push token, uses kRPC event streams).
 */
expect object PushTokenRegistrar {
    suspend fun registerIfNeeded(clientId: String, deviceTokenService: IDeviceTokenService)
}
