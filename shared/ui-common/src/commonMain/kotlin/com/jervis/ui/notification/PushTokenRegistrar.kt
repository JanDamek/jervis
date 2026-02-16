package com.jervis.ui.notification

import com.jervis.service.IDeviceTokenService

/**
 * Platform-specific push token registration.
 *
 * On Android: retrieves FCM token, registers with backend if needed.
 * On JVM/iOS: no-op (desktop uses kRPC streams, iOS not yet implemented).
 */
expect object PushTokenRegistrar {
    suspend fun registerIfNeeded(clientId: String, deviceTokenService: IDeviceTokenService)
}
