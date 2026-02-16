package com.jervis.ui.notification

import com.jervis.dto.notification.DeviceTokenDto
import com.jervis.service.IDeviceTokenService

actual object PushTokenRegistrar {
    private var lastRegisteredKey: String? = null

    actual suspend fun registerIfNeeded(clientId: String, deviceTokenService: IDeviceTokenService) {
        try {
            val token = IosTokenHolder.apnsToken
            val deviceId = IosTokenHolder.deviceId

            if (token.isNullOrBlank() || deviceId.isNullOrBlank()) {
                println("APNs token not available yet, skipping registration")
                return
            }

            // Dedup: don't re-register same token for same client
            val key = "$clientId:$token"
            if (key == lastRegisteredKey) {
                println("APNs token already registered for client $clientId")
                return
            }

            val result = deviceTokenService.registerToken(
                DeviceTokenDto(
                    clientId = clientId,
                    token = token,
                    platform = "ios",
                    deviceId = deviceId,
                ),
            )

            if (result.success) {
                lastRegisteredKey = key
                println("APNs token registered for client $clientId, device $deviceId")
            } else {
                println("APNs token registration failed: ${result.message}")
            }
        } catch (e: Exception) {
            println("APNs token registration error: ${e.message}")
        }
    }
}
