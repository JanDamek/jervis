package com.jervis.ui.notification

import com.google.firebase.messaging.FirebaseMessaging
import com.jervis.dto.notification.DeviceTokenDto
import com.jervis.service.IDeviceTokenService
import kotlinx.coroutines.tasks.await

actual object PushTokenRegistrar {
    actual suspend fun registerIfNeeded(clientId: String, deviceTokenService: IDeviceTokenService) {
        try {
            val context = AndroidContextHolder.applicationContext

            // Get current FCM token (may trigger initial fetch)
            val token = FirebaseMessaging.getInstance().token.await()
            FcmTokenStorage.saveToken(context, token)

            if (!FcmTokenStorage.needsRegistration(context, clientId)) {
                println("FCM token already registered for client $clientId")
                return
            }

            val deviceId = FcmTokenStorage.getOrCreateDeviceId(context)
            val result = deviceTokenService.registerToken(
                DeviceTokenDto(
                    clientId = clientId,
                    token = token,
                    platform = "android",
                    deviceId = deviceId,
                ),
            )

            if (result.success) {
                FcmTokenStorage.markRegistered(context, clientId, token)
                println("FCM token registered for client $clientId, device $deviceId")
            } else {
                println("FCM token registration failed: ${result.message}")
            }
        } catch (e: Exception) {
            println("FCM token registration error: ${e.message}")
        }
    }
}
