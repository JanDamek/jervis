package com.jervis.ui.notification

import com.google.firebase.messaging.FirebaseMessaging
import com.jervis.dto.notification.DeviceContextDto
import com.jervis.dto.notification.DeviceTokenDto
import com.jervis.service.notification.IDeviceTokenService
import kotlinx.coroutines.tasks.await

actual object PushTokenRegistrar {
    actual suspend fun registerTokenIfNeeded(deviceTokenService: IDeviceTokenService) {
        try {
            val context = AndroidContextHolder.applicationContext

            // Get current FCM token (may trigger initial fetch)
            val token = FirebaseMessaging.getInstance().token.await()
            FcmTokenStorage.saveToken(context, token)

            if (!FcmTokenStorage.needsRegistration(context)) {
                // Same token already on the server — nothing to do
                return
            }

            val deviceId = FcmTokenStorage.getOrCreateDeviceId(context)
            val result = deviceTokenService.registerToken(
                DeviceTokenDto(
                    deviceId = deviceId,
                    token = token,
                    platform = "android",
                ),
            )

            if (result.success) {
                FcmTokenStorage.markRegistered(context, token)
                println("FCM token registered, device $deviceId")
            } else {
                println("FCM token registration failed: ${result.message}")
            }
        } catch (e: Exception) {
            println("FCM token registration error: ${e.message}")
        }
    }

    actual suspend fun announceContext(clientId: String, deviceTokenService: IDeviceTokenService) {
        try {
            val context = AndroidContextHolder.applicationContext
            val deviceId = FcmTokenStorage.getOrCreateDeviceId(context)
            val result = deviceTokenService.setActiveContext(
                DeviceContextDto(deviceId = deviceId, clientId = clientId),
            )
            if (!result.success) {
                println("Device context announce failed: ${result.message}")
            }
        } catch (e: Exception) {
            println("Device context announce error: ${e.message}")
        }
    }
}
